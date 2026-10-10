import os
ma_path = "D:/inspection-camera/android/app/src/main/java/com/ttdk1507d/inspectioncamera/firebase/FirebaseManager.kt"
with open(ma_path, "r", encoding="utf-8") as f:
    content = f.read()

content = content.replace("import com.google.firebase.database.FirebaseDatabase", "import com.google.firebase.database.FirebaseDatabase\nimport com.google.firebase.database.DatabaseReference")

with open(ma_path, "w", encoding="utf-8") as f:
    f.write(content)
