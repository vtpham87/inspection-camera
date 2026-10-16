' Inspection Camera Photo Server - Background Launcher
' Works for whichever folder this script lives in (dev or prod): it only manages ITS OWN main.py.
Option Explicit

Dim wmi, colProcesses, fso0
Dim wmiPattern, scriptDirW
Set fso0 = CreateObject("Scripting.FileSystemObject")
scriptDirW = fso0.GetParentFolderName(WScript.ScriptFullName)
wmiPattern = Replace(scriptDirW, "\", "\\") & "\\main.py"
Set wmi = GetObject("winmgmts:{impersonationLevel=impersonate}!\\.\root\cimv2")
Set colProcesses = wmi.ExecQuery("SELECT CommandLine FROM Win32_Process WHERE Name = 'python.exe' AND CommandLine LIKE '%" & wmiPattern & "%'")
If colProcesses.Count > 0 Then
    WScript.Quit 0
End If

Dim sh, fso, pythonExe, targetScript, scriptDir
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)

' Mount network drive Z: in background (non-blocking)
sh.Run "cmd.exe /c net use Z: \\T1507\Data /persistent:yes", 0, False

sh.CurrentDirectory = scriptDir

' Python: local venv first, then dev venv, then PATH
pythonExe = scriptDir & "\venv\Scripts\python.exe"
If Not fso.FileExists(pythonExe) Then
    pythonExe = "D:\inspection-camera\backend\venv\Scripts\python.exe"
End If
If Not fso.FileExists(pythonExe) Then
    pythonExe = "python.exe"
End If

targetScript = scriptDir & "\main.py"

sh.Run """" & pythonExe & """ """ & targetScript & """", 0, False
