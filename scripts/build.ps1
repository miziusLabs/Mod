param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$temporaryDirectory = Join-Path ([IO.Path]::GetTempPath()) ('minecraft-mcp-build-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
$initScript = Join-Path $temporaryDirectory 'artifact.gradle'
$artifactRecord = Join-Path $temporaryDirectory 'artifact.txt'

try {
    $initScriptContents = @'
gradle.projectsEvaluated {
    def project = gradle.rootProject
    project.tasks.register('clipboardBuildArtifact') {
        dependsOn project.tasks.named('build')
        doLast {
            def jarTask = project.tasks.named('jar').get()
            def artifact = jarTask.archiveFile.get().asFile
            if (!artifact.isFile()) throw new GradleException("Missing mod JAR: ${artifact}")
            new File(project.property('clipboardArtifactFile').toString()).setText(artifact.absolutePath, 'UTF-8')
        }
    }
}
'@
    [IO.File]::WriteAllText($initScript, $initScriptContents, (New-Object Text.UTF8Encoding $false))

    Push-Location $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments --init-script $initScript "-PclipboardArtifactFile=$artifactRecord" clipboardBuildArtifact
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        Pop-Location
    }

    $artifact = [IO.File]::ReadAllText($artifactRecord, [Text.Encoding]::UTF8)
    if (!(Test-Path -LiteralPath $artifact -PathType Leaf)) { throw "Missing mod JAR: $artifact" }

    # Windows PowerShell provides an STA thread and a persistent FileDrop clipboard.
    $escapedArtifact = $artifact.Replace("'", "''")
    $clipboardScript = @"
`$ErrorActionPreference = 'Stop'
`$ProgressPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Windows.Forms
`$files = New-Object System.Collections.Specialized.StringCollection
[void]`$files.Add('$escapedArtifact')
[System.Windows.Forms.Clipboard]::SetFileDropList(`$files)
if (![System.Windows.Forms.Clipboard]::ContainsFileDropList()) { throw 'Clipboard did not accept the file.' }
"@
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($clipboardScript))
    & "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -STA -EncodedCommand $encodedCommand
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the JAR file to the clipboard.' }
    Write-Host "Built and copied JAR file to clipboard: $artifact"
} finally {
    foreach ($temporaryFile in @($initScript, $artifactRecord)) {
        if (Test-Path -LiteralPath $temporaryFile -PathType Leaf) {
            Remove-Item -LiteralPath $temporaryFile -Force
        }
    }
    Remove-Item -LiteralPath $temporaryDirectory -Force
}
