package com.xtract.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes files to the public Downloads/XTract folder through MediaStore (no storage permission needed). */
final class Downloads {

    interface Body {
        void writeTo(OutputStream out) throws IOException;
    }

    private Downloads() {}

    static Uri save(Context ctx, String fileName, String mimeType, Body body) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/XTract");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Could not create " + fileName + " in Downloads");
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("Could not open " + fileName);
            body.writeTo(out);
        } catch (IOException | RuntimeException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
        return uri;
    }

    static Uri saveText(Context ctx, String fileName, final String text) throws IOException {
        return save(ctx, fileName, "text/plain", new Body() {
            @Override
            public void writeTo(OutputStream out) throws IOException {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        });
    }
}
