package com.bantu.droid;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

/**
 * WebView dashboard for viewing the Bantu server's admin panel.
 * Loads localhost:8080 (or user-configured URL) to show the
 * DDNS status, visitor logs, and server controls.
 */
public class DashboardActivity extends AppCompatActivity {

    private WebView webView;
    private ProgressBar progressBar;
    private EditText urlBar;
    private ImageButton btnGo, btnRefresh, btnBack;

    private static final String DEFAULT_URL = "http://localhost:8080";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        webView = findViewById(R.id.webview);
        progressBar = findViewById(R.id.progress_bar);
        urlBar = findViewById(R.id.url_bar);
        btnGo = findViewById(R.id.btn_go);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnBack = findViewById(R.id.btn_back);

        // Configure WebView
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        // WebView client for in-app navigation
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                progressBar.setVisibility(View.GONE);
            }
        });

        // Progress indicator
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });

        // URL bar actions
        btnGo.setOnClickListener(v -> loadUrl());
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                loadUrl();
                return true;
            }
            return false;
        });

        btnRefresh.setOnClickListener(v -> webView.reload());
        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });

        // Load default URL
        urlBar.setText(DEFAULT_URL);
        webView.loadUrl(DEFAULT_URL);
    }

    private void loadUrl() {
        String url = urlBar.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
            urlBar.setText(url);
        }
        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
