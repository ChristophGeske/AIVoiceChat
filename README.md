# 🎙️ AIVoiceChat — Real‑time Voice Chat with Gemini 2.5 Pro and GPT‑5 (Android)


## 💡 What is AIVoiceChat?

When you have tried OpenAI's "Advanced Voice" or Google's "Gemini Live," you might have noticed that the quality of the answers is not as good as the thinking models Gemini 2.5 Pro and GPT-5, which both have higher thinking capabilities than the current voice models.

The reason for that is that when you use the voice option from these companies, they use a different model that is optimized for low-latency streaming rather than using the long “thinking” mode, which gives better answers but can take up to minutes.

AIVoiceChat wants to fix that and allows you to talk directly to the stronger text models (Gemini 2.5 Pro and GPT-5) so you get the highest possible quality answers, with the disadvantage of slower response times. 

Alternative: The best alternative to this project currently available is the voice typing mode in the Gemini app. 

<img width="208" height="384" alt="gemini" src="https://github.com/user-attachments/assets/7d91b730-f915-4ce5-b92a-46390ebb47ff" />

The Gemini app in my tests works best when you open the app on the phone, choose 2.5 pro model and use hey google to start chatting with it.

However the rate limitations of the Gemini app are very strict so you are almost required to buy a subscription to use it in practice and you have less control over the output like speed, length and style of answer which you can set in this project in the app.

The Google Gemini app has the advantage of being more polished, and having better voice recognition software (stt) running in the cloud and more natural sounding text-to-speech (tts) output voice. These two features are something I hope to improve next as well. Also you can type as an alternative and insert files which this project doesn't support currently.

Some further disadvantages of this project app is that you need to speak very clearly for the speech-to-text model to work well. 

All tone or emotion in your voice is lost and can't be picked up by this voice chat app yet. 

## 🚀 Get Started (Early Alpha)

1. Download the APK  
   - Go to the [Release Page](https://github.com/ChristophGeske/AIVoiceChat/tags)  
   - Download and install the APK `app-release.apk`

2. Get your free Gemini API key  
   - Google AI Studio: https://aistudio.google.com/apikey  
   - Sign in → Create API key → Copy it to your phone's clipboard

3. (Optional) Get an OpenAI API key  
   - OpenAI: https://platform.openai.com/  
   - Note: OpenAI usage is paid; ensure billing is enabled

4. Install & use AIVoiceChat  
   - Install the APK and grant microphone permission  
   - Open the app and paste your API key under "Show Settings"  
   - Pick your model (Gemini 2.5 Pro, Gemini 2.5 Flash, GPT‑5, or GPT‑5 mini)  
   - Tap “Tap to Speak” to talk to the model

5. Check back for updates  
   - Improvements ship frequently. Star the repo and watch the releases page!


## 🛠️ How It Works (Under the Hood)

- Speech‑to‑Text (STT): Uses Android’s built‑in SpeechRecognizer (US English can be set by you in your phone settings) or the much better Gemini 2.5 Live Flash multimodel. In one test i found online they predesessor of gemini live performed very well in speach recognition [Source](https://voicewriter.io/blog/best-speech-recognition-api-2025) and in my testing it often picked up my speach correctly. Maybe there are even better tools out there but for now I am happy eith the speach recognition quality and speed of the grmini live model.
- LLM calls: Uses Google and OpenAI servers for generating responses (Should adjust automatically to your spoken language)
- TTS: Android TextToSpeech (US English can be set by you in your phone settings)

SentenceTurnEngine:  
   - Sends a strict instruction so the model returns exactly one sentence in JSON:  
     `{"sentence":"<one concise sentence>","is_final_sentence":true|false}`  
   - Requests the next sentence when the previous one finishes TTS, this keeps conversation fast


- STT and TTS quality can vary by device/network settings but might run locally which would make it super fast
- Early alpha: occasional hiccups with streaming, pacing, or rate limits. The chosen sentence-by-sentence generation used is low on tokens but high on requests which for Gemini free tier might be less ideal since Gemini free tier is counting requests and not tokens.



## 🤝 Contributing

Issues and PRs are welcome. If you have ideas for better streaming, pacing, or UX, please open an issue to discuss. The app is still in Alpha so many improvements are already planned.


## 📜 License (MIT)

MIT License

Copyright (c) 2025 [Christoph Geske]

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the “Software”), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
