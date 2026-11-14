"""Render draft vertical demo clips from generated SnapVault screenshots (no personal media)."""
import argparse
from pathlib import Path
import subprocess

STORIES = (
    ('backup-before-2027', 'dashboard.png', 'Your Memories.\nYour own backup.', (
        'January 2027: some Memories\nwill be archived, not deleted.',
        'Request a full Snapchat export.\nDownload every ZIP part.',
        'Restore a local library.\nKeep a second verified copy.',
    )),
    ('captions-and-dates', 'overlay-demo.gif', 'Keep the moment.\nKeep the caption.', (
        'Before: a photo and detached caption.\nGenerated sample media.',
        'After: the caption is combined.\nActual SnapVault compositor output.',
        'Capture time, UTC offset and GPS\nverified on the combined JPEG.',
    )),
    ('free-local-library', 'library.png', 'Free. Local.\nYours to keep.', (
        'Snapchat Memories backup\nfor macOS, Windows and Linux.',
        'No processing paywall.\nNo SnapVault account required.',
        'Review your restored library.\nKeep your original export too.',
    )),
)


def build_commands(source: Path, output: Path, font: Path) -> list[list[str]]:
    for _, filename, _, _ in STORIES:
        if not (source / filename).is_file():
            raise FileNotFoundError(source / filename)
    for slug, _, _, _ in STORIES:
        for suffix in ('.mp4', '-title.txt', '-0.txt', '-1.txt', '-2.txt'):
            if (output / (slug + suffix)).exists():
                raise FileExistsError(output / (slug + suffix))
    output.mkdir(parents=True, exist_ok=True)
    commands = []
    for slug, filename, title, captions in STORIES:
        title_path = output / (slug + '-title.txt')
        title_path.write_text(title)
        filters = [
            'scale=1000:900:force_original_aspect_ratio=decrease',
            'pad=1080:1920:(ow-iw)/2:(oh-ih)/2:color=0x081425',
            f"drawtext=fontfile='{font}':text='SnapVault':fontsize=44:fontcolor=0xA78BFA:x=60:y=70",
            f"drawtext=fontfile='{font}':textfile='{title_path}':fontsize=64:fontcolor=white:x=60:y=180:line_spacing=14",
            f"drawtext=fontfile='{font}':text='snapvault.naj-dev.com':fontsize=36:fontcolor=0xA78BFA:x=60:y=1800",
        ]
        for index, caption in enumerate(captions):
            caption_path = output / f'{slug}-{index}.txt'
            caption_path.write_text(caption)
            filters.append(f"drawtext=fontfile='{font}':textfile='{caption_path}':fontsize=36:fontcolor=white:x=60:y=1500:line_spacing=12:enable='gte(t,{index * 6})*lt(t,{(index + 1) * 6})'")
        loop = ['-stream_loop', '-1'] if filename.endswith('.gif') else ['-loop', '1']
        commands.append(['ffmpeg', '-v', 'error', '-n', *loop, '-i', str(source / filename),
                         '-t', '18', '-vf', ','.join(filters), '-r', '24', '-an',
                         '-c:v', 'libx264', '-preset', 'veryfast', '-crf', '23', '-threads', '2',
                         '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(output / (slug + '.mp4'))])
    return commands


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path, help='Directory containing dashboard.png, library.png, overlay-demo.gif')
    parser.add_argument('output', type=Path)
    parser.add_argument('--font', type=Path)
    args = parser.parse_args()
    font = args.font or next(iter(Path('/usr/share/fonts').rglob('DejaVuSans.ttf')), None)
    if font is None or not font.is_file():
        parser.error('Choose a readable TrueType font with --font')
    for command in build_commands(args.source.resolve(), args.output.resolve(), font):
        subprocess.run(command, check=True)


if __name__ == '__main__':
    main()
