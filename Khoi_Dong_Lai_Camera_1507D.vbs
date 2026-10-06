Option Explicit
Dim sh, http
Set sh = CreateObject("WScript.Shell")
sh.Run "cscript //nologo ""D:\inspection-camera\backend\restart_background.vbs""", 0, True
WScript.Sleep 2500

On Error Resume Next
Set http = CreateObject("MSXML2.ServerXMLHTTP.6.0")
http.Open "POST", "http://127.0.0.1:8095/api/sync-vehicles-now", False
http.Send
On Error Goto 0

sh.Popup "Da khoi dong lai dich vu va dong bo danh sach xe len dien thoai thanh cong!", 4, "Camera 15-07D", 64
