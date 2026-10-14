#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

# Use the native Windows FileDrop clipboard when running in Git Bash/MSYS.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        exec powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$project_root/scripts/build.ps1")" "$@"
        ;;
esac

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "$temporary_directory"' EXIT
artifact_record="$temporary_directory/artifact.txt"
cat > "$temporary_directory/artifact.gradle" <<'GRADLE'
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
GRADLE

cd -- "$project_root"
bash ./gradlew "$@" --init-script "$temporary_directory/artifact.gradle" "-PclipboardArtifactFile=$artifact_record" clipboardBuildArtifact
artifact="$(cat "$artifact_record")"
[[ -f "$artifact" ]] || { printf 'Missing mod JAR: %s\n' "$artifact" >&2; exit 1; }

case "$(uname -s)" in
    Darwin)
        osascript - "$artifact" <<'APPLESCRIPT'
on run arguments
    set the clipboard to (POSIX file (item 1 of arguments))
end run
APPLESCRIPT
        ;;
    Linux)
        # File managers consume a file URI clipboard, rather than a plain path.
        file_uri='file://'
        # Encode each UTF-8 byte, preserving path separators and URI-safe characters.
        LC_ALL=C
        for ((index = 0; index < ${#artifact}; index++)); do
            character="${artifact:index:1}"
            case "$character" in
                [a-zA-Z0-9/._~-]) file_uri+="$character" ;;
                *) printf -v encoded_byte '%%%02X' "'$character"; file_uri+="$encoded_byte" ;;
            esac
        done
        if [[ -n "${WAYLAND_DISPLAY:-}" ]] && command -v wl-copy >/dev/null; then
            printf '%s\r\n' "$file_uri" | wl-copy --type text/uri-list
        elif [[ -n "${DISPLAY:-}" ]] && command -v xclip >/dev/null; then
            printf '%s\r\n' "$file_uri" | xclip -selection clipboard -t text/uri-list
        else
            echo 'File clipboard support requires a desktop session and wl-copy (Wayland) or xclip (X11).' >&2
            exit 1
        fi
        ;;
    *)
        echo 'File clipboard support is unavailable on this operating system.' >&2
        exit 1
        ;;
esac
printf 'Built and copied JAR file to clipboard: %s\n' "$artifact"
