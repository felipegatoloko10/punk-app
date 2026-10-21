import unittest
import tkinter as tk
from desktop import Desktop

class FakeConnection:
    def status(self): return {'configured':True,'online':True,'phone':False,'health':{},'account':{}}

class DesktopUi(unittest.TestCase):
    def test_account_fields_clear_without_starting_pc_services(self):
        root=tk.Tk();root.withdraw()
        panel=Desktop(root,FakeConnection())
        try:
            panel.account_action('login')
            self.assertIn('Preencha',panel.account_notice.get())
            self.assertEqual(panel.password.get(),'')
            self.assertEqual(len(panel.account_buttons),4)
            root.update_idletasks()
        finally: panel.close()
