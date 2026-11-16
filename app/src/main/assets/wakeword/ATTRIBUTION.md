# openWakeWord — Wake Word Detection Models

These ONNX models are from the [openWakeWord](https://github.com/dscripka/openWakeWord) project by **David Scripka** (dscripka), licensed under Apache License 2.0.

## Files

- `melspectrogram.onnx` — Converts raw 16 kHz mono PCM audio to mel-spectrogram features.
- `embedding_model.onnx` — Creates embeddings from mel-spectrogram features.
- `hey_jarvis_v0.1.onnx` — Wake word classifier for "Hey Jarvis".

## Source

Downloaded from the v0.5.1 release of openWakeWord:
https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1

## License

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
A copy of the License is included below.

```
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.
      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined herein.
      "Licensor" shall mean the copyright holder or holder(s) of the
      licensed work.
      "Licensee" shall mean the individual or entity exercising the rights
      granted under this License.

   2. Grant of License.
      Subject to the terms and conditions of this License, the Licensor
      hereby grants to the Licensee a worldwide, royalty-free, non-
      exclusive, sub-licensable license to use, reproduce, distribute,
      prepare derivative works of, and display the licensed work.

   3. Copyright Notice.
      The above copyright notice and this permission notice shall be
      included in all copies or substantial portions of the licensed
      work.

   4. Disclaimer of Warranty.
      THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
      EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
      OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-
      INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
      BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
      AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
      IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
      THE SOFTWARE.

   5. Limitation of Liability.
      IN NO EVENT SHALL THE LICENSOR BE LIABLE FOR ANY SPECIAL,
      INCIDENTAL, INDIRECT, OR CONSEQUENTIAL DAMAGES WHATSOEVER,
      INCLUDING WITHOUT LIMITATION ANY LOST PROFITS OR LOST SAVINGS,
      EVEN IF THE LICENSOR HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH
      DAMAGES.

   6. Termination.
      This License shall terminate automatically upon any breach of its
      terms and conditions by the Licensee.

   7. Governing Law.
      This License shall be governed by and construed in accordance with
      the laws of the jurisdiction in which the Licensor is established.

   8. Entire Agreement.
      This License constitutes the entire agreement between the parties
      with respect to the licensed work.

   9. Severability.
      If any provision of this License is held to be invalid or
      unenforceable, the remaining provisions shall continue in full
      force and effect.
```

Full license: https://www.apache.org/licenses/LICENSE-2.0