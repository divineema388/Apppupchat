package com.dealabs.pupchat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "PupChatWebView";
    private static final String BASE_URL = "https://pupchat.infy.uk";
    private static final String LOGIN_URL_PATH = "/login.php";
    private static final String HOME_URL_PATH = "/home.php";

    private WebView webView;
    private ProgressBar progressBar;
    private TextView tvError;
    private SwipeRefreshLayout swipeRefreshLayout;

    // For file uploads
    private ValueCallback<Uri[]> mUploadMessage;
    private String mCameraPhotoPath;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    // For permissions
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private String[] requestedPermissions;
    private GeolocationPermissions.Callback geolocationCallback;
    private String geolocationOrigin;
    private PermissionRequest currentPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        tvError = findViewById(R.id.tvError);
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout);

        setupWebView();
        setupSwipeRefreshLayout();
        registerFileChooserLauncher();

        // Check for initial internet connectivity
        if (isNetworkAvailable()) {
            loadInitialUrl();
        } else {
            showError(getString(R.string.no_internet_connection));
        }

        // Request permissions on app startup if needed
        checkAndRequestPermissions();
    }

    private void setupWebView() {
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true); // For local storage on the website
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
        webView.getSettings().setGeolocationEnabled(true); // Enable geolocation
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false); // Allow media playback without user interaction

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebViewClient(new CustomWebViewClient());
        webView.setWebChromeClient(new CustomWebChromeClient());
    }

    private void setupSwipeRefreshLayout() {
        if (swipeRefreshLayout != null) {
            swipeRefreshLayout.setOnRefreshListener(() -> {
                if (isNetworkAvailable()) {
                    webView.reload(); // Reload the current page
                } else {
                    swipeRefreshLayout.setRefreshing(false); // Stop refresh animation
                    showError(getString(R.string.no_internet_connection));
                }
            });
            swipeRefreshLayout.setColorSchemeResources(R.color.purple_500, R.color.teal_200); // Customize spinner colors
        } else {
            Log.w(TAG, "SwipeRefreshLayout not found in layout. Pull-to-refresh will not work.");
        }
    }

    private void registerFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (mUploadMessage == null) return;

                    Uri[] results = null;
                    if (result.getResultCode() == RESULT_OK) {
                        if (result.getData() == null) {
                            // If there is no data, then we may have taken a photo
                            if (mCameraPhotoPath != null) {
                                results = new Uri[]{Uri.parse(mCameraPhotoPath)};
                            }
                        } else {
                            String dataString = result.getData().getDataString();
                            if (dataString != null) {
                                results = new Uri[]{Uri.parse(dataString)};
                            } else {
                                // Handle multiple selections if applicable
                                if (result.getData().getClipData() != null) {
                                    int numSelectedFiles = result.getData().getClipData().getItemCount();
                                    results = new Uri[numSelectedFiles];
                                    for (int i = 0; i < numSelectedFiles; i++) {
                                        results[i] = result.getData().getClipData().getItemAt(i).getUri();
                                    }
                                }
                            }
                        }
                    }
                    mUploadMessage.onReceiveValue(results);
                    mUploadMessage = null;
                    mCameraPhotoPath = null; // Clear camera path
                });
    }

    private void loadInitialUrl() {
        // Check if the user has previously logged in
        // This is a simple example; you might use SharedPreferences for more robust state management
        // For now, we'll just check if the URL contains "home.php" which would imply logged in state
        String lastKnownUrl = getSharedPreferences("app_prefs", MODE_PRIVATE)
                                .getString("last_url", BASE_URL + LOGIN_URL_PATH);

        if (lastKnownUrl.contains(HOME_URL_PATH) && !lastKnownUrl.contains(LOGIN_URL_PATH)) {
            webView.loadUrl(BASE_URL + HOME_URL_PATH);
        } else {
            webView.loadUrl(BASE_URL + LOGIN_URL_PATH);
        }
    }

    private class CustomWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            showLoadingSpinner();
            hideError();
            // Optional: Save the current URL as the last known URL if it's a "home" or "logged in" page
            if (url.contains(HOME_URL_PATH) || url.contains(LOGIN_URL_PATH)) {
                 getSharedPreferences("app_prefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_url", url)
                        .apply();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            hideLoadingSpinner();
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false); // Stop refresh animation
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            hideLoadingSpinner();
            if (swipeRefreshLayout != null) {
                swipeRefreshLayout.setRefreshing(false);
            }

            if (request.isForMainFrame()) {
                if (!isNetworkAvailable()) {
                    showError(getString(R.string.no_internet_connection));
                } else {
                    // Handle specific errors from WebView if necessary
                    showError(getString(R.string.web_page_error) + " Error: " + error.getErrorCode());
                }
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // For older APIs, you might need to handle request.getUrl().toString()
            String url = request.getUrl().toString();
            if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("geo:")) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true; // Indicate that the URL is handled
            }
            return false; // Let WebView handle other URLs
        }
    }

    private class CustomWebChromeClient extends WebChromeClient {
        // For file uploads (input type="file")
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (mUploadMessage != null) {
                mUploadMessage.onReceiveValue(null); // Cancel any previous request
            }
            mUploadMessage = filePathCallback;

            Intent contentSelectionIntent = new Intent(Intent.ACTION_GET_CONTENT);
            contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE);
            contentSelectionIntent.setType("*/*"); // Allow all file types

            List<Intent> intentArray = new ArrayList<>();

            // If the website requests image/video/audio specifically, offer camera/camcorder/microphone
            if (fileChooserParams.getAcceptTypes() != null && fileChooserParams.getAcceptTypes().length > 0) {
                for (String acceptType : fileChooserParams.getAcceptTypes()) {
                    if (acceptType.startsWith("image/") || acceptType.equals("*/*")) {
                        Intent takePhotoIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        if (takePhotoIntent.resolveActivity(getPackageManager()) != null) {
                            File photoFile = null;
                            try {
                                photoFile = createImageFile();
                            } catch (IOException ex) {
                                Log.e(TAG, "Unable to create Image File", ex);
                            }
                            if (photoFile != null) {
                                mCameraPhotoPath = FileProvider.getUriForFile(
                                        MainActivity.this,
                                        getApplicationContext().getPackageName() + ".fileprovider",
                                        photoFile
                                ).toString();
                                takePhotoIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(photoFile));
                                intentArray.add(takePhotoIntent);
                            }
                        }
                    }
                    if (acceptType.startsWith("video/") || acceptType.equals("*/*")) {
                        Intent takeVideoIntent = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                        if (takeVideoIntent.resolveActivity(getPackageManager()) != null) {
                            intentArray.add(takeVideoIntent);
                        }
                    }
                    if (acceptType.startsWith("audio/") || acceptType.equals("*/*")) {
                        Intent recordAudioIntent = new Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION);
                        if (recordAudioIntent.resolveActivity(getPackageManager()) != null) {
                            intentArray.add(recordAudioIntent);
                        }
                    }
                }
            }


            Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
            chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent);
            chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray.toArray(new Intent[0]));

            fileChooserLauncher.launch(chooserIntent);
            return true;
        }

        // For Geolocation Permissions
        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            Log.d(TAG, "onGeolocationPermissionsShowPrompt for origin: " + origin);
            final String permission = Manifest.permission.ACCESS_FINE_LOCATION;
            if (ContextCompat.checkSelfPermission(MainActivity.this, permission) == PackageManager.PERMISSION_GRANTED) {
                // Permission already granted
                callback.invoke(origin, true, false); // Allow geolocation, don't retain
            } else {
                // Request permission
                geolocationOrigin = origin;
                geolocationCallback = callback;
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{permission}, PERMISSION_REQUEST_CODE);
            }
        }

        // For camera/microphone/other media permissions requested by the web page
        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            String[] resources = request.getResources();
            List<String> neededPermissions = new ArrayList<>();
            for (String resource : resources) {
                if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    neededPermissions.add(Manifest.permission.CAMERA);
                } else if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    neededPermissions.add(Manifest.permission.RECORD_AUDIO);
                }
                // Add more resource types and their corresponding Android permissions if needed
            }

            if (!neededPermissions.isEmpty()) {
                currentPermissionRequest = request; // Store the request
                // Convert list to array
                requestedPermissions = neededPermissions.toArray(new String[0]);
                checkAndRequestPermissions(); // Use your existing permission check/request logic
            } else {
                request.deny(); // Deny if no specific Android permission is mapped
            }
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            super.onPermissionRequestCanceled(request);
            Log.d(TAG, "Permission request cancelled.");
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
            Log.d(TAG, consoleMessage.message() + " -- From line " + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
            return true; // Consume the message
        }
    }

    // --- Permission Handling ---
    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // General permissions for file upload, audio, location
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA);
        }


        // Add permissions requested by WebChromeClient's onPermissionRequest
        if (requestedPermissions != null) {
            for (String perm : requestedPermissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(perm);
                }
            }
            requestedPermissions = null; // Clear after adding
        }


        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permissionsToRequest.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                Toast.makeText(this, "Permissions granted!", Toast.LENGTH_SHORT).show();
                // If geolocation permission was just granted, invoke the callback
                if (geolocationCallback != null) {
                    geolocationCallback.invoke(geolocationOrigin, true, false);
                    geolocationCallback = null;
                    geolocationOrigin = null;
                }
                // If a WebChromeClient permission was just granted, approve the request
                if (currentPermissionRequest != null) {
                    currentPermissionRequest.grant(currentPermissionRequest.getResources());
                    currentPermissionRequest = null;
                }
            } else {
                Toast.makeText(this, getString(R.string.grant_permissions_message), Toast.LENGTH_LONG).show();

                // If geolocation permission was denied
                if (geolocationCallback != null) {
                    geolocationCallback.invoke(geolocationOrigin, false, false); // Deny geolocation
                    geolocationCallback = null;
                    geolocationOrigin = null;
                }
                // If a WebChromeClient permission was denied
                if (currentPermissionRequest != null) {
                    currentPermissionRequest.deny(); // Deny the request
                    currentPermissionRequest = null;
                }

                // Optional: Show a dialog explaining why permissions are needed
                boolean showRationale = false;
                for (String permission : permissions) {
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        showRationale = true;
                        break;
                    }
                }
                if (showRationale) {
                    new AlertDialog.Builder(this)
                            .setTitle("Permission Needed")
                            .setMessage(getString(R.string.grant_permissions_message))
                            .setPositiveButton("Grant", (dialog, which) -> checkAndRequestPermissions())
                            .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                            .create()
                            .show();
                }
            }
        }
    }


    // --- Utility Methods ---
    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File imageFile = File.createTempFile(imageFileName, ".jpg", storageDir);
        return imageFile;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager != null) {
            NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
            return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
        return false;
    }

    private void showLoadingSpinner() {
        progressBar.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE); // Hide WebView while loading
        tvError.setVisibility(View.GONE);
    }

    private void hideLoadingSpinner() {
        progressBar.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE); // Show WebView when loaded
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE); // Hide WebView on error
        progressBar.setVisibility(View.GONE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
        // Don't show WebView yet, as it might still be loading
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}