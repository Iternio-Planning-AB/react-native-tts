import { NativeModules, NativeEventEmitter, Platform, EmitterSubscription, NativeModule } from 'react-native';

interface TextToSpeechModule extends NativeModule {
  getInitStatus(): Promise<boolean>;
  requestInstallEngine(): Promise<boolean>;
  requestInstallData(): Promise<boolean>;
  setDucking(enabled: boolean): Promise<boolean>;
  setDefaultEngine(engineName: string): Promise<boolean>;
  setDefaultVoice(voiceId: string): Promise<boolean>;
  setDefaultRate(rate: number, skipTransform?: boolean): Promise<boolean>;
  setDefaultPitch(pitch: number): Promise<boolean>;
  setDefaultLanguage(language: string): Promise<boolean>;
  setIgnoreSilentSwitch(ignoreSilentSwitch: IgnoreSilentSwitch): Promise<boolean>;
  voices(): Promise<Voice[]>;
  getDefaultVoiceIdentifier(language: string): Promise<string>;
  engines(): Promise<Engine[]>;
  speak(utterance: string, options?: Pick<SpeakOptions, 'iosVoiceId' | 'rate'> | AndroidOptions): Promise<string | number>;
  stop(onWordBoundary?: boolean): Promise<boolean>;
  pause(onWordBoundary?: boolean): Promise<boolean>;
  resume(): Promise<boolean>;
}

const TextToSpeech = NativeModules.TextToSpeech as TextToSpeechModule;

type SimpleEvents = "tts-start" | "tts-finish" | "tts-error" | "tts-cancel";
type SimpleEvent = {
  utteranceId: string | number;
};

type ProgressEventName = "tts-progress";
type ProgressEvent = {
  utteranceId: string | number;
  location: number;
  length: number;
};

export type TtsEvents = SimpleEvents | ProgressEventName;

export type TtsEvent<T extends TtsEvents = TtsEvents> = T extends ProgressEventName
  ? ProgressEvent
  : SimpleEvent;

export type TtsEventHandler<T extends TtsEvents = TtsEvents> = (
  event: TtsEvent<T>
) => void;

export type TtsError = {
  code:
    | "no_engine"
    | "error"
    | "not_ready"
    | "invalid_request"
    | "network_error"
    | "network_timeout"
    | "not_installed_yet"
    | "output_error"
    | "service_error"
    | "synthesis_error"
    | "lang_missing_data"
    | "lang_not_supported"
    | "Android AudioManager error"
    | "not_available"
    | "not_found"
    | "bad_rate";
  message: string;
};

export type IOSSilentSwitchBehavior = "inherit" | "ignore" | "obey";

export type Voice = {
  id: string;
  name: string;
  language: string;
  quality: number;
  latency: number;
  networkConnectionRequired: boolean;
  notInstalled: boolean;
};

export type Engine = {
  name: string;
  label: string;
  default: boolean;
  icon: number;
};

export type AndroidOptions = {
  /** Parameter key to specify the audio stream type to be used when speaking text or playing back a file */
  KEY_PARAM_STREAM?:
    | "STREAM_VOICE_CALL"
    | "STREAM_SYSTEM"
    | "STREAM_RING"
    | "STREAM_MUSIC"
    | "STREAM_ALARM"
    | "STREAM_NOTIFICATION"
    | "STREAM_DTMF"
    | "STREAM_ACCESSIBILITY";
  /** Parameter key to specify the speech volume relative to the current stream type volume used when speaking text. Volume is specified as a float ranging from 0 to 1 where 0 is silence, and 1 is the maximum volume (the default behavior). */
  KEY_PARAM_VOLUME?: number;
  /** Parameter key to specify how the speech is panned from left to right when speaking text. Pan is specified as a float ranging from -1 to +1 where -1 maps to a hard-left pan, 0 to center (the default behavior), and +1 to hard-right. */
  KEY_PARAM_PAN?: number;
};

export type SpeakOptions = {
  iosVoiceId?: string;
  rate?: number;
  androidParams?: AndroidOptions;
};

type IgnoreSilentSwitch = 'inherit' | 'ignore' | 'obey';

class Tts extends NativeEventEmitter {
  constructor() {
    super(TextToSpeech);
  }

  getInitStatus(): Promise<boolean> {
    if (Platform.OS === 'ios' || Platform.OS === 'windows') {
      return Promise.resolve(true);
    }
    return TextToSpeech.getInitStatus();
  }

  requestInstallEngine(): Promise<boolean> {
    if (Platform.OS === 'ios' || Platform.OS === 'windows') {
      return Promise.resolve(true);
    }
    return TextToSpeech.requestInstallEngine();
  }

  requestInstallData(): Promise<boolean> {
    if (Platform.OS === 'ios' || Platform.OS === 'windows') {
      return Promise.resolve(true);
    }
    return TextToSpeech.requestInstallData();
  }

  setDucking(enabled: boolean): Promise<boolean> {
    if (Platform.OS === 'windows') {
      return Promise.resolve(true);
    }
    return TextToSpeech.setDucking(enabled);
  }

  setDefaultEngine(engineName: string): Promise<boolean> {
    if (Platform.OS === 'ios' || Platform.OS === 'windows') {
      return Promise.resolve(true);
    }
    return TextToSpeech.setDefaultEngine(engineName);
  }

  setDefaultVoice(voiceId: string): Promise<boolean> {
    return TextToSpeech.setDefaultVoice(voiceId);
  }

  setDefaultRate(rate: number, skipTransform?: boolean): Promise<boolean> {
    return TextToSpeech.setDefaultRate(rate, !!skipTransform);
  }

  setDefaultPitch(pitch: number): Promise<boolean> {
    return TextToSpeech.setDefaultPitch(pitch);
  }

  setDefaultLanguage(language: string): Promise<boolean> {
    return TextToSpeech.setDefaultLanguage(language);
  }

  setIgnoreSilentSwitch(ignoreSilentSwitch: IgnoreSilentSwitch): Promise<boolean> {
    if (Platform.OS === 'ios' || Platform.OS === 'windows') {
      return TextToSpeech.setIgnoreSilentSwitch(ignoreSilentSwitch);
    }
    return Promise.resolve(true);
  }

  voices(): Promise<Voice[]> {
    return TextToSpeech.voices();
  }

  getDefaultVoiceIdentifier(language: string): Promise<string> {
    return TextToSpeech.getDefaultVoiceIdentifier(language);
  }

  engines(): Promise<Engine[]> {
    if (Platform.OS === 'ios' || Platform.OS === 'windows') {
      return Promise.resolve([]);
    }
    return TextToSpeech.engines();
  }

  speak(utterance: string, options?: SpeakOptions | string): Promise<string | number> {
    // compatibility with old-style voiceId argument passing
    if (typeof options === 'string') {
      if (Platform.OS === 'ios') {
        return TextToSpeech.speak(utterance, { iosVoiceId: options });
      } else {
        return TextToSpeech.speak(utterance, {});
      }
    } else {
      if (Platform.OS === 'ios' || Platform.OS === 'windows') {
        return TextToSpeech.speak(utterance, options);
      } else {
        return TextToSpeech.speak(utterance, options?.androidParams || {});
      }
    }
  }

  stop(onWordBoundary?: boolean): Promise<boolean> {
    if (Platform.OS === 'ios') {
      return TextToSpeech.stop(onWordBoundary);
    } else {
      return TextToSpeech.stop();
    }
  }

  pause(onWordBoundary?: boolean): Promise<boolean> {
    if (Platform.OS === 'ios') {
      return TextToSpeech.pause(onWordBoundary);
    }
    return Promise.resolve(false);
  }

  resume(): Promise<boolean> {
    if (Platform.OS === 'ios') {
      return TextToSpeech.resume();
    }
    return Promise.resolve(false);
  }

  addEventListener<T extends TtsEvents>(type: T, handler: TtsEventHandler<T>): EmitterSubscription {
    return this.addListener(type, handler);
  }
}

export default new Tts();
