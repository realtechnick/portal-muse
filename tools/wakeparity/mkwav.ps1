# Makes the WAVs under android/wake/src/test/resources/parity that did not come from
# zserge/microwakeword: "hey muse" and ordinary speech from Windows' built-in voices, white noise and
# silence. All are 16 kHz mono PCM16. Run from this directory in Windows PowerShell.
param([string]$Out = "..\..\android\wake\src\test\resources\parity")

Add-Type -AssemblyName System.Speech
$Out = (Resolve-Path $Out).Path
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)

function Speak([string]$voice, [string]$body, [string]$file) {
  $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
  $s.SelectVoice($voice)
  $s.SetOutputToWaveFile((Join-Path $Out $file), $format)
  $s.SpeakSsml("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>$body</speak>")
  $s.Dispose()
}

$wake = "<break time='1000ms'/>Hey Muse<break time='1500ms'/>Hey Muse<break time='1500ms'/>"
Speak "Microsoft David Desktop" $wake "hey_muse_david.wav"
Speak "Microsoft Zira Desktop" $wake "hey_muse_zira.wav"
Speak "Microsoft Zira Desktop" "<break time='500ms'/>The weather today is mild, with a light breeze from the west and some clouds by the evening. Remember to water the plants before you leave.<break time='500ms'/>" "speech_zira.wav"

function WriteWav([string]$file, [int16[]]$samples) {
  $w = New-Object System.IO.BinaryWriter([System.IO.File]::Create((Join-Path $Out $file)))
  $w.Write([Text.Encoding]::ASCII.GetBytes("RIFF")); $w.Write([int](36 + 2 * $samples.Length))
  $w.Write([Text.Encoding]::ASCII.GetBytes("WAVEfmt ")); $w.Write([int]16)
  $w.Write([int16]1); $w.Write([int16]1); $w.Write([int]16000); $w.Write([int]32000); $w.Write([int16]2); $w.Write([int16]16)
  $w.Write([Text.Encoding]::ASCII.GetBytes("data")); $w.Write([int](2 * $samples.Length))
  foreach ($v in $samples) { $w.Write($v) }
  $w.Dispose()
}

$rng = New-Object System.Random(20261018)
$noise = New-Object int16[] 80000
for ($i = 0; $i -lt $noise.Length; $i++) { $noise[$i] = [int16]$rng.Next(-3000, 3001) }
WriteWav "noise.wav" $noise
WriteWav "silence.wav" (New-Object int16[] 48000)
