Option Explicit
Dim fs, shell, root, python, entry
Set fs = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
root = fs.GetParentFolderName(WScript.ScriptFullName)
python = shell.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\hermes\hermes-agent\venv\Scripts\pythonw.exe"
entry = fs.BuildPath(root, "bridge\desktop.py")
If Not fs.FileExists(python) Then
    MsgBox "O Punk Desktop precisa de uma instalacao local do Hermes para iniciar.", 48, "Punk Desktop"
    WScript.Quit 1
End If
shell.Run Chr(34) & python & Chr(34) & " " & Chr(34) & entry & Chr(34), 1, False
