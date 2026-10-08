' Inspection Camera Photo Server - Background Restarter
Option Explicit
On Error Resume Next

Dim wmi, colProcesses, proc
Set wmi = GetObject("winmgmts:{impersonationLevel=impersonate}!\\.\root\cimv2")
Set colProcesses = wmi.ExecQuery("SELECT ProcessId FROM Win32_Process WHERE Name = 'python.exe' AND CommandLine LIKE '%inspection-camera%main.py%'")
For Each proc in colProcesses
    proc.Terminate()
Next

WScript.Sleep 2000

On Error Goto 0
Dim sh, fso, pythonExe, targetScript
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

sh.CurrentDirectory = "D:\inspection-camera\backend"

pythonExe = "D:\inspection-camera\backend\venv\Scripts\python.exe"
If Not fso.FileExists(pythonExe) Then
    pythonExe = "C:\Users\t1507d\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe"
End If
If Not fso.FileExists(pythonExe) Then
    pythonExe = "python.exe"
End If

targetScript = "D:\inspection-camera\backend\main.py"

sh.Run """" & pythonExe & """ """ & targetScript & """", 0, False
