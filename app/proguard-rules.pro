# libink.so resolves Java classes by name through JNI FindClass, and looks their
# fields and methods up with GetFieldID / GetMethodID. R8 cannot see any of that,
# so without these rules it renames them and every stroke operation dies at
# startup with a ClassNotFoundException from native code.
#
# The list is not guesswork. It comes from the strings in the shipped library:
#
#   python -c "import re;d=open('lib/arm64-v8a/libink.so','rb').read();\
#     print(sorted({m.decode() for m in re.findall(rb'androidx/ink[A-Za-z0-9_/$]*', d)}))"
#
# which names classes in exactly these three packages. Re-run it after upgrading
# androidx.ink and widen this list if the library starts reaching for more.
-keep class androidx.ink.brush.** { *; }
-keep class androidx.ink.geometry.** { *; }
-keep class androidx.ink.strokes.** { *; }
