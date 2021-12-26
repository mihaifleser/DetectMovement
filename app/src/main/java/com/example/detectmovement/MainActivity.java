package com.example.detectmovement;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import com.example.detectmovement.databinding.ActivityMainBinding;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private final Integer REQUEST_IMAGE_CAPTURE = 1;
    private FirebaseStorage firebaseStorage = FirebaseStorage.getInstance();
    FirebaseAuth mAuth = FirebaseAuth.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);

        binding.takePhotoButton.setOnClickListener(e -> {
            dispatchTakePictureIntent();
        });

        binding.uploadButton.setOnClickListener(e -> {
            uploadPicture();
        });

        binding.recordButton.setOnClickListener(e -> {
            startDetectingMovement();
        });

    }


    @Override
    protected void onStart() {
        super.onStart();
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) {
            // do your stuff
        } else {
            signInAnonymously();
        }
    }

    private void startDetectingMovement()
    {
        Intent intent = new Intent(this, DetectMovementActivity.class);
        startActivity(intent);
    }

    private void signInAnonymously() {
        mAuth.signInAnonymously().addOnSuccessListener(this, new  OnSuccessListener<AuthResult>() {
            @Override
            public void onSuccess(AuthResult authResult) {
                // do your stuff
            }
        })
                .addOnFailureListener(this, new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception exception) {
                        exception.printStackTrace();
                    }
                });
    }

    private void uploadPicture()
    {
        Bitmap capture = Bitmap.createBitmap(binding.imageView.getWidth(),binding.imageView.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas captureCanvas = new Canvas(capture);
        binding.imageView.draw(captureCanvas);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        capture.compress(Bitmap.CompressFormat.PNG,100,outputStream);
        byte[] data = outputStream.toByteArray();
        String path = "photos/" + UUID.randomUUID() + ".png";

        StorageReference photosRef = firebaseStorage.getReference(path);

        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("MM/dd/yyyy");
        String date = simpleDateFormat.format(calendar.getTime());
        StorageMetadata metadata = new StorageMetadata.Builder().setCustomMetadata(
                "date",date
        ).build();

        UploadTask uploadTask = photosRef.putBytes(data,metadata);
        binding.uploadButton.setEnabled(false);
        binding.progressBar.setVisibility(View.VISIBLE);
        uploadTask.addOnCompleteListener(this, new OnCompleteListener<UploadTask.TaskSnapshot>() {
            @Override
            public void onComplete(@NonNull Task<UploadTask.TaskSnapshot> task) {
                System.out.println("Upload succeded");
            }
        });
        Task<Uri> getDownloadUriTask = uploadTask.continueWithTask(new Continuation<UploadTask.TaskSnapshot, Task<Uri>>() {
            @Override
            public Task<Uri> then(@NonNull Task<UploadTask.TaskSnapshot> task) throws Exception {
                if(!task.isSuccessful())
                {
                    throw task.getException();
                }
                return photosRef.getDownloadUrl();
            }
        });
        getDownloadUriTask.addOnCompleteListener(this, new OnCompleteListener<Uri>() {
            @Override
            public void onComplete(@NonNull Task<Uri> task) {
                if(task.isSuccessful()){
                    Uri downloadUri = task.getResult();
                    binding.urlText.setText("Upload Link: " + downloadUri.toString());
                }
                binding.uploadButton.setEnabled(true);
                binding.progressBar.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } catch (ActivityNotFoundException e) {
            // display error state to the user
        }
    }
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            Bundle extras = data.getExtras();
            Bitmap imageBitmap = (Bitmap) extras.get("data");
            binding.imageView.setImageBitmap(imageBitmap);
        }
    }
}