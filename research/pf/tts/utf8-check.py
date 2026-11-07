# PF T4 Step 3 stand-in: what Model.rawRequest does to a WAV reply. A non-streamed reply is collected as bytes and
# handed over as whole.toString(UTF_8) (Model.kt:216); Java replaces each invalid UTF-8 sequence with U+FFFD, so the
# caller can't get the bytes back. Python's errors="replace" follows the same rule.
import sys
d = open(sys.argv[1], "rb").read()
s = d.decode("utf-8", errors="replace")
back = s.encode("utf-8")
print("wav bytes:", len(d), "| after String round trip:", len(back), "| identical:", back == d,
      "| replacement chars:", s.count("�"))
