package com.mizius.mcp;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Reads only the configured client's current log, with bounded memory and IO. */
final class ClientLogs {
    static final int MAX_SCAN_BYTES=16*1024*1024;
    private final Path file;
    ClientLogs(Path file) { this.file=file; }

    JsonObject read(JsonObject arguments) throws IOException {
        int limit=arguments.has("limit") ? ClientControl.integer(arguments,"limit",1,1000) : 100;
        List<String> queries=new ArrayList<>();
        if(arguments.has("queries")) {
            if(!arguments.get("queries").isJsonArray()) throw new IllegalArgumentException("queries must be an array of strings");
            JsonArray values=arguments.getAsJsonArray("queries");
            if(values.size()>16) throw new IllegalArgumentException("At most 16 queries are allowed");
            for(JsonElement value:values) {
                if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().length()>256)
                    throw new IllegalArgumentException("Each query must be a string of at most 256 characters");
                queries.add(value.getAsString().toLowerCase(Locale.ROOT));
            }
        }
        ArrayDeque<String> matches=new ArrayDeque<>();
        long size;
        int scanned;
        boolean limited=false;
        try(RandomAccessFile log=new RandomAccessFile(file.toFile(),"r")) {
            size=log.length();
            scanned=(int)Math.min(size,MAX_SCAN_BYTES);
            long offset=size-scanned;
            log.seek(offset);
            byte[] tail=new byte[scanned];
            // Handle rotation or truncation while the client writes to its log.
            int count=0, read;
            while(count<tail.length && (read=log.read(tail,count,tail.length-count))>0) count+=read;
            scanned=count;
            try(BufferedReader lines=new BufferedReader(new StringReader(new String(tail,0,count,StandardCharsets.UTF_8)))) {
                if(offset>0) lines.readLine();
                String line;
                while((line=lines.readLine())!=null) {
                    String lower=line.toLowerCase(Locale.ROOT);
                    if(queries.isEmpty() || queries.stream().anyMatch(lower::contains)) {
                        if(matches.size()==limit) { matches.removeFirst(); limited=true; }
                        matches.addLast(line);
                    }
                }
            }
        }
        JsonObject result=new JsonObject();
        JsonArray lines=new JsonArray(); matches.forEach(lines::add); result.add("lines",lines);
        result.addProperty("returned",matches.size()); result.addProperty("limit",limit);
        result.addProperty("truncated",size>MAX_SCAN_BYTES || limited);
        result.addProperty("scanned_bytes",scanned); result.addProperty("file_bytes",size);
        result.addProperty("file","logs/latest.log");
        return result;
    }
}
