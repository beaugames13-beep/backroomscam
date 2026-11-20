# Backrooms Camcorder (Fabric, MC 1.21.11)

Craft: 1 Spyglass + 1 Redstone (shapeless).
Right-click while holding = raise/lower camera. Left-click while raised = start/stop recording.
Recording saves PNG frames (~10 fps) to <game dir>/camcorder/rec_<timestamp>/
Make a video:  ffmpeg -framerate 10 -i frame_%05d.png -c:v libx264 -pix_fmt yuv420p out.mp4

Build: push to GitHub -> Actions tab -> "Build mod" -> download the "backroomscam-jar" artifact.
Requires Fabric Loader + Fabric API on 1.21.11.
