/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.github.brandon.fingerprintauthenticationdialog;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

/**
 * A dialog which uses fingerprint APIs to authenticate the user, and falls back to password
 * authentication if fingerprint is not available.
 */
@TargetApi(Build.VERSION_CODES.M)
public class FingerprintDialog extends DialogFragment implements FingerprintCallback {

    private boolean isAvailable = false;

    public static final int PERMISSION_USE_FINGERPRINT = 200;
    //    /** Alias for our key in the Android Key Store */
    private static final String KEY_NAME = "fingerprint_key";

    private FingerprintCallback mFingerprintCallback;
    private Button mCancelButton;
    private TextView mFingerprintContent;

    private FingerprintManager mFingerManager;
    private FingerprintManager.CryptoObject mCryptoObject;
    private FingerprintUiHelper mFingerprintUiHelper;
    private Activity mActivity;
    private String mTitle = "";
    private String mContent = "";
    private String mCancel = "";

    private FingerprintUiHelper.FingerprintUiHelperBuilder mFingerprintUiHelperBuilder;
    private KeyStore mKeyStore;
    private KeyGenerator mKeyGenerator;
    private Cipher mCipher;

    public static class Builder {
        private final FingerprintManager mFingerPrintManager;
        private final FragmentManager mFragmentManager;
        private FingerprintDialog mFingerprintDialog;

        public Builder(Activity activity) {
            mFragmentManager = activity.getFragmentManager();
            mFingerPrintManager = (FingerprintManager) activity.getSystemService(Context.FINGERPRINT_SERVICE);
            mFingerprintDialog = new FingerprintDialog();
        }

        public void show() {
            mFingerprintDialog.show(mFragmentManager, "FINGERPRINT_TAG");
        }

        public void dismiss() {
            mFingerprintDialog.dismiss();
        }

        public void setNegativeButton(String text) {
            mFingerprintDialog.setCancel(text);
        }

        public FingerprintDialog build(String title, String content, FingerprintCallback fingerprintCallback) {
            mFingerprintDialog.setFingerManager(mFingerPrintManager);
            mFingerprintDialog.setTitle(title);
            mFingerprintDialog.setContent(content);
            mFingerprintDialog.setCancelable(false);
            mFingerprintDialog.setOnFingerprintListener(fingerprintCallback);
            if(mFingerprintDialog.isAvailable())
                return mFingerprintDialog;
            else
                return null;
        }
    }

    private void setOnFingerprintListener(FingerprintCallback fingerprintListener) {
        this.mFingerprintCallback = fingerprintListener;
    }

    public FingerprintDialog() {
        try {
            mKeyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            mKeyStore = KeyStore.getInstance("AndroidKeyStore");
            mCipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                    + KeyProperties.BLOCK_MODE_CBC + "/"
                    + KeyProperties.ENCRYPTION_PADDING_PKCS7);
            createKey();
            if(initCipher())
                setCryptoObject();
        } catch (NoSuchAlgorithmException | NoSuchProviderException | KeyStoreException | NoSuchPaddingException e) {
            throw new RuntimeException("Failed at FingerprintDialog()", e);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFingerprintUiHelperBuilder = new FingerprintUiHelper.FingerprintUiHelperBuilder(mFingerManager);
        // Do not create a new Fragment when the Activity is re-created such as orientation changes.
        setRetainInstance(true);
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        getDialog().setTitle(mTitle);
        View v = inflater.inflate(R.layout.fingerprint_authentication_dialog, container, false);
        mCancelButton = (Button) v.findViewById(R.id.cancel_button);
        mCancelButton.setText(mCancel);
        mCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mFingerprintUiHelper.stopListening();
                dismiss();
            }
        });
        mFingerprintContent = (TextView)v.findViewById(R.id.fingerprint_description);
        mFingerprintContent.setText(mContent);
        mFingerprintUiHelper = mFingerprintUiHelperBuilder.build(mActivity, (ImageView) v.findViewById(R.id.fingerprint_icon), (TextView) v.findViewById(R.id.fingerprint_status), this);

        return v;
    }

    @Override
    public void onResume() {
        super.onResume();
        mFingerprintUiHelper.startListening(mCryptoObject);
    }

    @Override
    public void onPause() {
        super.onPause();
        mFingerprintUiHelper.stopListening();
        dismiss();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mActivity = (Activity) activity;

    }

    @Override
    public void onAuthenticated() {
        // Callback from FingerprintUiHelper. Let the activity know that authentication was
        // successful.
        mFingerprintCallback.onAuthenticated();
        dismiss();
    }

    @Override
    public void onError(int msgId) {
        mFingerprintCallback.onError(msgId);
    }

    /**
     * Initialize the {@link Cipher} instance with the created key in the {@link #createKey()}
     * method.
     *
     * @return {@code true} if initialization is successful, {@code false} if the lock screen has
     * been disabled or reset after the key was generated, or if a fingerprint got enrolled after
     * the key was generated.
     */
    private boolean initCipher() {
        try {
            mKeyStore.load(null);
            SecretKey key = (SecretKey) mKeyStore.getKey(KEY_NAME, null);
            mCipher.init(Cipher.ENCRYPT_MODE, key);
            setAvailable(true);
            return true;
        } catch (KeyPermanentlyInvalidatedException e) {
            return false;
        } catch (KeyStoreException | CertificateException | UnrecoverableKeyException | IOException
                | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to init Cipher", e);
        }
    }

    /**
     * Creates a symmetric key in the Android Key Store which can only be used after the user has
     * authenticated with fingerprint.
     */

    private void createKey() {
        // The enrolling flow for fingerprint. This is where you ask the user to set up fingerprint
        // for your flow. Use of keys is necessary if you need to know if the set of
        // enrolled fingerprints has changed.
        try {
            mKeyStore.load(null);
            // Set the alias of the entry in Android KeyStore where the key will appear
            // and the constrains (purposes) in the constructor of the Builder
            mKeyGenerator.init(new KeyGenParameterSpec.Builder(KEY_NAME,
                    KeyProperties.PURPOSE_ENCRYPT |
                            KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    // Require the user to authenticate with a fingerprint to authorize every use
                    // of the key
                    .setUserAuthenticationRequired(true)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .build());
            mKeyGenerator.generateKey();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | CertificateException | IOException e) {
            throw new RuntimeException("Failed to createKey", e);
        }
    }

    private void setCryptoObject() {
        mCryptoObject = new FingerprintManager.CryptoObject(mCipher);
    }

    private void setFingerManager(FingerprintManager fingerManager) {
        this.mFingerManager = fingerManager;
    }

    private boolean isAvailable() {
        return isAvailable;
    }

    private void setAvailable(boolean available) {
        isAvailable = available;
    }

    private void setTitle(String title) {
        this.mTitle = title;
    }

    private void setContent(String content) {
        this.mContent = content;
    }

    public void setCancel(String cancel) {
        this.mCancel = cancel;
    }
}
