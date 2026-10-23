Option Explicit
Dim shell, fs, base, pythonw, command, argument
Set shell = CreateObject("WScript.Shell")
Set fs = CreateObject("Scripting.FileSystemObject")
base = fs.GetParentFolderName(WScript.ScriptFullName)
pythonw = shell.ExpandEnvironmentStrings("%LIVING_PLAYER_PYTHONW%")
If pythonw = "%LIVING_PLAYER_PYTHONW%" Then pythonw = "pythonw.exe"
command = Chr(34) & pythonw & Chr(34) & " " & Chr(34) & base & "\tools\web_desktop.py" & Chr(34)
For Each argument In WScript.Arguments
    command = command & " " & Chr(34) & Replace(argument, Chr(34), Chr(34) & Chr(34)) & Chr(34)
Next
shell.CurrentDirectory = base
On Error Resume Next
shell.Run command, 1, False
If Err.Number <> 0 Then
    MsgBox "Python windowed launcher was not found. Install Python or use the standalone EXE edition.", 16, "Living Room Player"
End If
