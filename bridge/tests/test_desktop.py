import unittest
import tkinter as tk
from desktop import Desktop

class FakeConnection:
    def status(self): return {'configured':True,'online':True,'phone':False,'health':{},'account':{}}
    def manage(self): return {'devices':[],'integrations':[],'calls':[],'audit':[]}

class DesktopUi(unittest.TestCase):
    def test_account_fields_clear_without_starting_pc_services(self):
        root=tk.Tk();root.withdraw()
        panel=Desktop(root,FakeConnection())
        try:
            panel.account_action('login')
            self.assertIn('Preencha',panel.account_notice.get())
            self.assertEqual(panel.password.get(),'')
            self.assertEqual(len(panel.account_buttons),4)
            panel.open_management()
            root.update_idletasks()
            self.assertEqual(set(panel.management_trees),{'devices','integrations','calls','audit'})
            self.assertTrue(panel.client_readonly.get())
            root.update_idletasks()
        finally: panel.close()
