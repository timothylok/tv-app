package com.superghosttim.aichannels;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Tiny HTTPS GET for the small static JSON files. Blocking: call from a background thread only. */
final class Net {
    static final class Result {
        String body;
        String date;
        long ageSeconds;
        long sentLocal, recvLocal;
    }

    private static final int MAX_BYTES = 1 << 20;

    static Result get(String url) throws IOException {
        if (!ScheduleModel.isHttps(url)) throw new IOException("https only");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            Result r = new Result();
            r.sentLocal = System.currentTimeMillis();
            int code = c.getResponseCode();
            r.recvLocal = System.currentTimeMillis();
            if (code != 200) throw new IOException("HTTP " + code);
            r.date = c.getHeaderField("Date");
            try { r.ageSeconds = Long.parseLong(c.getHeaderField("Age")); } catch (RuntimeException e) { r.ageSeconds = 0; }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > MAX_BYTES) throw new IOException("too large");
                }
            }
            r.body = bo.toString("UTF-8");
            return r;
        } finally {
            c.disconnect();
        }
    }
}
