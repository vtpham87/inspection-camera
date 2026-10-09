' Inspection Camera Photo Server - Background Launcher
Option Explicit

Dim wmi, colProcesses
Set wmi = GetObject("winmgmts:{impersonationLevel=impersonate}!\\.\root\cimv2")
Set colProcesses = wmi.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE Name = 'python.exe' AND CommandLine LIKE '%inspection-camera%main.py%'")
If colProcesses.Count > 0 Then
    WScript.Quit 0
End If

Dim sh, fso, pythonExe, targetScript
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

' Đảm bảo ổ đĩa mạng Z: được kết nối sau khi máy tính khởi động lại
Dim i
For i = 1 To 10
    If fso.FolderExists("Z:\DataPTCGDB") Then Exit For
    sh.Run "cmd.exe /c net use Z: \\T1507\Data /persistent:yes", 0, True
    WScript.Sleep 2000
Next

sh.CurrentDirectory = "D:\inspection-camera-prod\backend"

pythonExe = "D:\inspection-camera\backend\venv\Scripts\python.exe"
If Not fso.FileExists(pythonExe) Then
    pythonExe = "C:\Users\t1507d\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe"
End If
If Not fso.FileExists(pythonExe) Then
    pythonExe = "python.exe"
End If

targetScript = "D:\inspection-camera\backend\main.py"

sh.Run """" & pythonExe & """ """ & targetScript & """", 0, False
