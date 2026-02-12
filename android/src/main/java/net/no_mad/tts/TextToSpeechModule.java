package net.no_mad.tts;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;

public class TextToSpeechModule extends ReactContextBaseJavaModule {

    private TextToSpeech tts;
    private Boolean ready = null;
    private final ArrayList<Promise> initStatusPromises;

    private boolean ducking = false;
    private final AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest = null;

    private final AudioAttributes audioAttributes;

    public TextToSpeechModule(ReactApplicationContext reactContext) {
        super(reactContext);
        audioManager = (AudioManager) reactContext.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        audioAttributes = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .build();

        initStatusPromises = new ArrayList<>();

        tts = new TextToSpeech(reactContext, status -> {
            synchronized(initStatusPromises) {
                ready = status == TextToSpeech.SUCCESS;
                for(Promise p: initStatusPromises) {
                    resolveReadyPromise(p);
                }
                initStatusPromises.clear();
            }
        });

        setUtteranceProgress();
    }

    private void setUtteranceProgress() {
        if(tts != null)
        {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    sendEvent("tts-start", utteranceId);
                }

                @Override
                public void onDone(String utteranceId) {
                    if(ducking) {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest);
                        audioFocusRequest = null;
                    }
                    sendEvent("tts-finish", utteranceId);
                }

                @Override
                public void onError(String utteranceId) {
                    if(ducking) {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest);
                        audioFocusRequest = null;
                    }
                    sendEvent("tts-error", utteranceId);
                }

                @Override
                public void onStop(String utteranceId, boolean interrupted) {
                    if(ducking) {
                        audioManager.abandonAudioFocusRequest(audioFocusRequest);
                        audioFocusRequest = null;
                    }
                    sendEvent("tts-cancel", utteranceId);
                }

                @Override
                public void onRangeStart (String utteranceId, int start, int end, int frame) {
                    WritableMap params = Arguments.createMap();
                    params.putString("utteranceId", utteranceId);
                    params.putInt("start", start);
                    params.putInt("end", end);
                    params.putInt("frame", frame);
                    params.putInt("length", end - start);
                    sendEvent("tts-progress", params);
                }
            });
        }
    }

    private void resolveReadyPromise(Promise promise) {
        if (ready == Boolean.TRUE) {
            promise.resolve("success");
        } else {
            promise.reject("no_engine", "No TTS engine installed");
        }
    }

    private static void resolvePromiseWithStatusCode(int statusCode, Promise promise) {
        switch (statusCode) {
            case TextToSpeech.SUCCESS:
                promise.resolve("success");
                break;
            case TextToSpeech.LANG_COUNTRY_AVAILABLE:
                promise.resolve("lang_country_available");
                break;
            case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
                promise.resolve("lang_country_var_available");
                break;
            case TextToSpeech.ERROR_INVALID_REQUEST:
                promise.reject("invalid_request", "Failure caused by an invalid request");
                break;
            case TextToSpeech.ERROR_NETWORK:
                promise.reject("network_error", "Failure caused by a network connectivity problems");
                break;
            case TextToSpeech.ERROR_NETWORK_TIMEOUT:
                promise.reject("network_timeout", "Failure caused by network timeout.");
                break;
            case TextToSpeech.ERROR_NOT_INSTALLED_YET:
                promise.reject("not_installed_yet", "Unfinished download of voice data");
                break;
            case TextToSpeech.ERROR_OUTPUT:
                promise.reject("output_error", "Failure related to the output (audio device or a file)");
                break;
            case TextToSpeech.ERROR_SERVICE:
                promise.reject("service_error", "Failure of a TTS service");
                break;
            case TextToSpeech.ERROR_SYNTHESIS:
                promise.reject("synthesis_error", "Failure of a TTS engine to synthesize the given input");
                break;
            case TextToSpeech.LANG_MISSING_DATA:
                promise.reject("lang_missing_data", "Language data is missing");
                break;
            case TextToSpeech.LANG_NOT_SUPPORTED:
                promise.reject("lang_not_supported", "Language is not supported");
                break;
            default:
                promise.reject("error", "Unknown error code: " + statusCode);
                break;
        }
    }

    private boolean isPackageInstalled(String packageName) {
        PackageManager pm = getReactApplicationContext().getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(packageName, 0);
            return true;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "TextToSpeech";
    }

    @ReactMethod
    public void getInitStatus(Promise promise) {
        synchronized(initStatusPromises) {
            if(ready == null) {
                initStatusPromises.add(promise);
            } else {
                resolveReadyPromise(promise);
            }
        }
    }

    @ReactMethod
    public void speak(String utterance, ReadableMap params, Promise promise) {
        if(notReady(promise)) return;

        if(ducking && audioFocusRequest == null) {
            // Request audio focus for playback
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .build();

            int requestStatus = audioManager.requestAudioFocus(audioFocusRequest);

            if(requestStatus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                promise.reject("request_focus_failed", "Android AudioManager error, failed to request audio focus");
                return;
            }
        }

        String utteranceId = Integer.toString(utterance.hashCode());

        int speakResult = speak(utterance, utteranceId, params);
        if(speakResult == TextToSpeech.SUCCESS) {
            promise.resolve(utteranceId);
        } else {
            resolvePromiseWithStatusCode(speakResult, promise);
        }
    }

    @ReactMethod
    public void setDefaultLanguage(String language, Promise promise) {
        if(notReady(promise)) return;

        Locale locale;

        if(language.contains("-")) {
            String[] parts = language.split("-");
            locale = new Locale(parts[0], parts[1]);
        } else {
            locale = new Locale(language);
        }

        try {
            int result = tts.setLanguage(locale);
            resolvePromiseWithStatusCode(result, promise);
        } catch (Exception e) {
            promise.reject("error", "Unknown error code");
        }
    }

    @ReactMethod
    public void setDucking(Boolean ducking, Promise promise) {
        if(notReady(promise)) return;
        this.ducking = ducking;
        promise.resolve("success");
    }

    @ReactMethod
    public void setDefaultRate(Float rate, Boolean skipTransform, Promise promise) {
        if(notReady(promise)) return;

        if(skipTransform) {
            int result = tts.setSpeechRate(rate);
            resolvePromiseWithStatusCode(result, promise);
        } else {
            // normalize android rate
            // rate value will be in the range 0.0 to 1.0
            // let's convert it to the range of values Android platform expects,
            // where 1.0 is no change of rate and 2.0 is the twice faster rate
            float androidRate = rate < 0.5f ?
                    rate * 2 : // linear fit {0, 0}, {0.25, 0.5}, {0.5, 1}
                    rate * 4 - 1; // linear fit {{0.5, 1}, {0.75, 2}, {1, 3}}
            int result = tts.setSpeechRate(androidRate);
            resolvePromiseWithStatusCode(result, promise);
        }
    }

    @ReactMethod
    public void setDefaultPitch(Float pitch, Promise promise) {
        if(notReady(promise)) return;
        int result = tts.setPitch(pitch);
        resolvePromiseWithStatusCode(result, promise);
    }

    @ReactMethod
    public void setDefaultVoice(String voiceId, Promise promise) {
        if(notReady(promise)) return;

        try {
            for (Voice voice : tts.getVoices()) {
                if (voice.getName().equals(voiceId)) {
                    int result = tts.setVoice(voice);
                    resolvePromiseWithStatusCode(result, promise);
                    return;
                }
            }
        } catch (Exception e) {
            // Purposefully ignore exceptions here due to some buggy TTS engines.
            // See http://stackoverflow.com/questions/26730082/illegalargumentexception-invalid-int-os-with-samsung-tts
        }
        promise.reject("not_found", "The selected voice was not found");
    }

    @ReactMethod
    public void getDefaultVoiceIdentifier(String language, Promise promise) {
        if(notReady(promise)) return;

        Voice currentVoice = tts.getVoice();
        if (currentVoice == null) {
            promise.reject("not_found", "Language not found");
            return;
        }
        promise.resolve(currentVoice.getName());
    }

    @ReactMethod
    public void voices(Promise promise) {
        if(notReady(promise)) return;

        WritableArray voiceArray = Arguments.createArray();

        try {
            for (Voice voice : tts.getVoices()) {
                WritableMap voiceMap = Arguments.createMap();
                voiceMap.putString("id", voice.getName());
                voiceMap.putString("name", voice.getName());

                String language = voice.getLocale().getLanguage();
                String country = voice.getLocale().getCountry();
                if (!country.isEmpty()) {
                    language += "-" + country;
                }

                voiceMap.putString("language", language);
                voiceMap.putInt("quality", voice.getQuality());
                voiceMap.putInt("latency", voice.getLatency());
                voiceMap.putBoolean("networkConnectionRequired", voice.isNetworkConnectionRequired());
                voiceMap.putBoolean("notInstalled", voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
                voiceArray.pushMap(voiceMap);
            }
        } catch (Exception e) {
            // Purposefully ignore exceptions here due to some buggy TTS engines.
            // See http://stackoverflow.com/questions/26730082/illegalargumentexception-invalid-int-os-with-samsung-tts
        }

        promise.resolve(voiceArray);
    }

    @ReactMethod
    public void setDefaultEngine(String engineName, final Promise promise) {
        if(notReady(promise)) return;

        if(isPackageInstalled(engineName)) {
            ready = null;
            invalidate();
            tts = new TextToSpeech(getReactApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    synchronized(initStatusPromises) {
                        ready = status == TextToSpeech.SUCCESS;
                        for(Promise p: initStatusPromises) {
                            resolveReadyPromise(p);
                        }
                        initStatusPromises.clear();
                        promise.resolve(ready);
                    }
                }
            }, engineName);

            setUtteranceProgress();
        } else {
            promise.reject("not_found", "The selected engine was not found");
        }
    }

    @ReactMethod
    public void engines(Promise promise) {
        if(notReady(promise)) return;

        WritableArray engineArray = Arguments.createArray();

        try {
            String defaultEngineName = tts.getDefaultEngine();
            for (TextToSpeech.EngineInfo engine : tts.getEngines()) {
                WritableMap engineMap = Arguments.createMap();

                engineMap.putString("name", engine.name);
                engineMap.putString("label", engine.label);
                engineMap.putBoolean("default", engine.name.equals(defaultEngineName));
                engineMap.putInt("icon", engine.icon);

                engineArray.pushMap(engineMap);
            }
        } catch (Exception e) {
            promise.reject("error", "Unknown error code");
        }

        promise.resolve(engineArray);
    }

    @ReactMethod
    public void stop(Promise promise) {
        if(notReady(promise)) return;

        int result = tts.stop();
        boolean resultValue = (result == TextToSpeech.SUCCESS) ? Boolean.TRUE : Boolean.FALSE;
        promise.resolve(resultValue);
    }

    @ReactMethod
    private void requestInstallEngine(Promise promise) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("market://details?id=com.google.android.tts"));
        try {
            startActivity(intent);
            promise.resolve("success");
        } catch (ActivityNotFoundException e) {
            promise.reject("error", "Could not open Google Text to Speech App in the Play Store");
        } catch (Exception e) {
            promise.reject("unknown_error", e.getMessage());
        }
    }

    @ReactMethod
    private void requestInstallData(Promise promise) {
        Intent intent = new Intent();
        intent.setAction(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
        try {
            startActivity(intent);
            promise.resolve("success");
        } catch (ActivityNotFoundException e) {
            promise.reject("no_engine", "No TTS engine installed");
        } catch (Exception e) {
            promise.reject("unknown_error", e.getMessage());
        }
    }

    /**
     * <a href="https://stackoverflow.com/questions/15563361/tts-leaked-serviceconnection">called on React Native Reloading JavaScript</a>
     */
    @Override
    public void invalidate() {
        if(tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private void startActivity(Intent intent) {
        Activity activity = getReactApplicationContext().getCurrentActivity();
        if (activity == null) {
            throw new ActivityNotFoundException();
        }
        activity.startActivity(intent);
    }

    private boolean notReady(Promise promise) {
        if(ready == null) {
            promise.reject("not_ready", "TTS is not ready");
            return true;
        }
        else if(!ready) {
            resolveReadyPromise(promise);
            return true;
        }
        return false;
    }

    private int speak(String utterance, String utteranceId, ReadableMap inputParams) {
        String audioStreamTypeString = inputParams.hasKey("KEY_PARAM_STREAM") ? inputParams.getString("KEY_PARAM_STREAM") : "";
        float volume = inputParams.hasKey("KEY_PARAM_VOLUME") ? (float) inputParams.getDouble("KEY_PARAM_VOLUME") : 1.0f;
        float pan = inputParams.hasKey("KEY_PARAM_PAN") ? (float) inputParams.getDouble("KEY_PARAM_PAN") : 0.0f;

        int audioStreamType = switch (Optional.ofNullable(audioStreamTypeString).orElse("")) {
            case "STREAM_ACCESSIBILITY" -> AudioManager.STREAM_ACCESSIBILITY;
            case "STREAM_ALARM" -> AudioManager.STREAM_ALARM;
            case "STREAM_DTMF" -> AudioManager.STREAM_DTMF;
            case "STREAM_MUSIC" -> AudioManager.STREAM_MUSIC;
            case "STREAM_NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION;
            case "STREAM_RING" -> AudioManager.STREAM_RING;
            case "STREAM_SYSTEM" -> AudioManager.STREAM_SYSTEM;
            case "STREAM_VOICE_CALL" -> AudioManager.STREAM_VOICE_CALL;
            default -> AudioManager.USE_DEFAULT_STREAM_TYPE;
        };

        tts.setAudioAttributes(audioAttributes);

        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, audioStreamType);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan);

        return tts.speak(utterance, TextToSpeech.QUEUE_ADD, params, utteranceId);
    }

    private void sendEvent(String eventName, String utteranceId) {
        WritableMap params = Arguments.createMap();
        params.putString("utteranceId", utteranceId);
        sendEvent(eventName, params);
    }

    private void sendEvent(String eventName, WritableMap params) {
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    @ReactMethod
    public void removeListeners(Integer count) {
        // Keep: Required for RN built in Event Emitter Calls.
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Keep: Required for RN built in Event Emitter Calls.
    }
}
