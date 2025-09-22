# 🎙️ AIVoiceChat — Real‑time Voice Chat with Gemini 2.5 Pro and GPT‑5 high (Android)


## 💡 What is AIVoiceChat?

When you ever tried OpenAIs "AdvancedVoice" or Googles "Gemini Live" you might have noticed that the quality of the answears is not as good as the Thinking models Gemini 2.5 Pro and GPT‑5 Thinking high. 

The reason for that is that when you use the voice option from these companies they use a different model that is optimized for low‑latency streaming rather than using the long “thinking” mode which gives better answears but can take up to minutes. 

AIVoiceChat talks directly to the stronger text models (Gemini 2.5 Pro and GPT‑5 high) so you get the highest possible quality answers with the disadventage of sometimes much slower responses.


## 🚀 Get Started (Early Alpha)

1. Download the APK  
   - Go to the latest releases: https://github.com/your-user/AIVoiceChat/releases  
   - Download and install the APK `app-release.apk`

2. Get your free Gemini API key  
   - Google AI Studio: https://aistudio.google.com/apikey  
   - Sign in → Create API key → Copy it in your phones Clipboard

3. (Optional) Get an OpenAI API key  
   - OpenAI: https://platform.openai.com/  
   - Note: OpenAI usage is paid; ensure billing is enabled

4. Install & use AIVoiceChat  
   - Install the APK and grant microphone permission  
   - Open the app and paste your API key under "Show Settings"  
   - Pick your model (Gemini 2.5 Pro, Gemini 2.5 Flash, GPT‑5, or GPT‑5 mini)  
   - Tap “Tap to Speak,” to talk to the model

5. Check back for updates  
   - Improvements ship frequently. Star the repo and watch the releases page!


## 🛠️ How It Works (Under the Hood)

- Speech‑to‑Text (STT): Uses Android’s built‑in SpeechRecognizer (US English can be set by you in your phone settings) 
- LLM calls: Use Google and OpenAI servers for generating responses (Should adjust automatically to your spoken language)
- TTS: Android TextToSpeech (US English can be set by you in your phone settings)

SentenceTurnEngine:  
   - Sends a strict instruction so the model returns exactly one sentence in JSON:  
     `{"sentence":"<one concise sentence>","is_final_sentence":true|false}`  
   - Requests the next sentence when the previous one finishes TTS this keeps conversation fast


- STT and TTS quality can vary by device/network settings but might run locally which would make it super fast
- Early alpha: occasional hiccups with streaming, pacing, or rate limits. The choosen sentence-by-sentence generation used is low on tokens but high on requests which for Gemini free tier might be less ideal since Gemini free tier is counting requests and not tokens.


## 🤝 Contributing

Issues and PRs are welcome. If you have ideas for better streaming, pacing, or UX, please open an issue to discuss. The app is still in Alpha so many improvements are already planed.


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
