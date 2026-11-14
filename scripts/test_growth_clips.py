import tempfile
import unittest
from pathlib import Path

from growth_clips import build_commands


class GrowthClipTests(unittest.TestCase):
    def test_three_vertical_clips_keep_inputs_and_refuse_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ('dashboard.png', 'library.png', 'overlay-demo.gif'):
                (root / name).write_bytes(b'fixture')
            commands = build_commands(root, root / 'output', Path('/font.ttf'))
            self.assertEqual(3, len(commands))
            for command in commands:
                self.assertEqual('ffmpeg', command[0])
                self.assertIn('-n', command)
                self.assertNotIn('-y', command)
                self.assertIn('1080:1920', ' '.join(command))
                self.assertIn('libx264', command)
            self.assertIn('-stream_loop', commands[1])
            self.assertEqual(b'fixture', (root / 'dashboard.png').read_bytes())

    def test_missing_source_fails_before_building_commands(self):
        with tempfile.TemporaryDirectory() as temp:
            with self.assertRaises(FileNotFoundError):
                build_commands(Path(temp), Path(temp) / 'output', Path('/font.ttf'))
