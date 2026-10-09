"""Local AI ad detection, and the timestamps it produces.

The detector reads an episode's transcript and asks SemIf one question per
stretch of it: is this an ad? SemIf answers from a small open model's option
logits in a single forward pass — no generated text, so no timestamps parsed
back out of prose. The timestamps come from the transcript itself.

It is heavy, so it runs only where its toolchain is installed (`install.sh
ai`), as a subprocess under that venv. What it finds is a list of spans per
episode, which replicate over the peer mesh like everything else in
lan/state.py, so a phone or a second desktop skips ads it never analysed.
"""
