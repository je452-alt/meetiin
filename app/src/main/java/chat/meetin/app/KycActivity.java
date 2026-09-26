package chat.meetin.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

public class KycActivity extends AppCompatActivity {
    private static final String TAG = "KycActivity";
    private static final int REQUEST_IMAGE_PICK = 2001;
    private static final int REQUEST_IMAGE_CAPTURE = 2002;
    private static final int REQUEST_CAMERA_PERMISSION = 2003;

    private ImageView previewImage;
    private Button cameraBtn, galleryBtn, submitBtn;
    private String selectedBase64 = null;
    private Uri cameraImageUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_kyc);

        previewImage = findViewById(R.id.previewImage);
        cameraBtn = findViewById(R.id.cameraBtn);
        galleryBtn = findViewById(R.id.galleryBtn);
        submitBtn = findViewById(R.id.submitBtn);

        cameraBtn.setOnClickListener(v -> openCamera());
        galleryBtn.setOnClickListener(v -> openGallery());
        submitBtn.setOnClickListener(v -> submitDocument());

        submitBtn.setEnabled(false);
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        try {
            File photoFile = new File(getExternalFilesDir(null), "kyc_photo.jpg");
            cameraImageUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", photoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri);
            startActivityForResult(intent, REQUEST_IMAGE_CAPTURE);
        } catch (Exception e) {
            Log.e(TAG, "Camera failed", e);
            Toast.makeText(this, "Camera error", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        try {
            Bitmap bitmap = null;

            if (requestCode == REQUEST_IMAGE_PICK && data != null) {
                Uri imageUri = data.getData();
                InputStream is = getContentResolver().openInputStream(imageUri);
                bitmap = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
            } else if (requestCode == REQUEST_IMAGE_CAPTURE) {
                InputStream is = getContentResolver().openInputStream(cameraImageUri);
                bitmap = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
            }

            if (bitmap != null) {
                bitmap = resizeBitmap(bitmap, 1280);
                previewImage.setImageBitmap(bitmap);
                previewImage.setVisibility(View.VISIBLE);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                byte[] bytes = baos.toByteArray();
                selectedBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP);

                submitBtn.setEnabled(true);
                Log.d(TAG, "Image ready: " + bytes.length + " bytes");
            }
        } catch (Exception e) {
            Log.e(TAG, "Image processing failed", e);
            Toast.makeText(this, "Failed to process image", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap resizeBitmap(Bitmap source, int maxWidth) {
        if (source.getWidth() <= maxWidth) return source;
        float ratio = (float) maxWidth / source.getWidth();
        int newHeight = (int) (source.getHeight() * ratio);
        return Bitmap.createScaledBitmap(source, maxWidth, newHeight, true);
    }

    private void submitDocument() {
        if (selectedBase64 == null) {
            Toast.makeText(this, "Please select a document first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, VerifyingActivity.class);
        intent.putExtra("documentBase64", selectedBase64);
        startActivity(intent);
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            }
        }
    }
}
