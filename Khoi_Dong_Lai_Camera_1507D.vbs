Option Explicit
Dim sh, http, i, isReady, syncSuccess
Set sh = CreateObject("WScript.Shell")

' 1. Goi restart backend ngam
sh.Run "cscript //nologo ""D:\inspection-camera\backend\restart_background.vbs""", 0, True

' 2. Cho server khoi dong va san sang (thu toi da 10 giay)
isReady = False
For i = 1 To 20
    WScript.Sleep 500
    On Error Resume Next
    Set http = CreateObject("MSXML2.ServerXMLHTTP.6.0")
    http.Open "GET", "http://127.0.0.1:8095/api/health", False
    http.setTimeouts 1000, 1000, 1000, 1000
    http.Send
    If Err.Number = 0 Then
        If http.Status = 200 Then
            isReady = True
            On Error Goto 0
            Exit For
        End If
    End If
    On Error Goto 0
Next

' 3. Neu server da bat, goi dong bo danh sach xe ngay lap tuc
syncSuccess = False
If isReady Then
    On Error Resume Next
    Set http = CreateObject("MSXML2.ServerXMLHTTP.6.0")
    http.Open "POST", "http://127.0.0.1:8095/api/sync-vehicles-now", False
    http.setTimeouts 2000, 2000, 5000, 5000
    http.Send
    If Err.Number = 0 And http.Status = 200 Then
        syncSuccess = True
    End If
    On Error Goto 0
End If

' 4. Thong bao ket qua
If syncSuccess Then
    sh.Popup "Da khoi dong lai dich vu va dong bo danh sach xe len dien thoai thanh cong!", 4, "Camera 15-07D", 64
ElseIf isReady Then
    sh.Popup "Dich vu da khoi dong nhung dong bo xe bi cham. Vui long kiem tra lai sau vai giay!", 5, "Camera 15-07D", 48
Else
    sh.Popup "Khong the khoi dong dich vu Camera 15-07D. Vui long bao ky thuat!", 6, "Camera 15-07D", 16
End If
