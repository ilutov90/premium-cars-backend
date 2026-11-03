package com.premiumcars.autoimport;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Component;

import com.premiumcars.catalog.domain.CarSource;

/**
 * The automatic import's download for checking what the runs queue: a ZIP with {@code listings.csv} (every listing
 * row of the source with its car, by link and by listing id) and {@code runs.csv} (the source's last runs with their
 * messages and per-make passes). CSV in UTF-8 with a BOM, so Excel shows Cyrillic and Chinese text.
 */
@Component
public class AutoImportExport {

    static final int RUNS = 50;

    private final AutoImportListingRepository listings;
    private final AutoImportRunRepository runs;

    public AutoImportExport(AutoImportListingRepository listings, AutoImportRunRepository runs) {
        this.listings = listings;
        this.runs = runs;
    }

    public void write(CarSource source, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry("listings.csv"));
            w.write('﻿');
            line(w, AutoImportListingRepository.EXPORT_COLUMNS);
            listings.export(source, r -> line(w, r));
            w.flush();
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("runs.csv"));
            w.write('﻿');
            line(w, AutoImportRunRepository.EXPORT_COLUMNS);
            runs.export(source, RUNS, r -> line(w, r));
            w.flush();
            zip.closeEntry();
        }
    }

    static void line(Writer w, List<String> cells) {
        try {
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) {
                    w.write(',');
                }
                w.write(cell(cells.get(i)));
            }
            w.write("\r\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** RFC 4180: quoted when it holds a comma, quote or line break. */
    static String cell(String v) {
        if (v == null) {
            return "";
        }
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
