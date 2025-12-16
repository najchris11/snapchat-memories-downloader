# Snapchat Memories Downloader
Since snapchat wants you to pay for more than 5gb of snapchat memories, I made a script to download all your memories since the version snapchat provided has a bug where it says 100% is downloaded but in reality it didn't download anything (at least in my case)

## Disclaimer
Everything i did here was vibe coded, i wanted it do be done quickly and it worked for me.
I think it's even better than the original, since I am adding metadata to the files, which snapchat doesn't
Feel free to contribute 👍🙏

# How to run
1. create a python venv
```bash
python3 -m venv .venv
```
2. activate python venv
```bash
# mac/linux
source .venv/bin/activate

# windows
.\.venv\Scripts\Activate.ps1
```
3. check python venv
```bash
# mac/linux
which python
# windows
Get-Command python
```

4. Install dependencies

**For Mac/Linux:**
```bash
chmod +x ./installer.sh
./installer.sh
```

helper if operation not permitted (mac):
```bash
xattr -d com.apple.quarantine ./installer.sh
```

**For Windows:**
```powershell
# Install Python packages
pip install requests beautifulsoup4

# Install exiftool via Chocolatey (if installed)
choco install exiftool

# Or install exiftool via WinGet
winget install exiftool

# Or install exiftool manually from https://exiftool.org/
```

5. request the download from snapchat
- go to [https://accounts.snapchat.com]()
- click on ``My Data```
- select ``Export your Memories`` and click ``Request Only Memories```
- select ``All Time``
- confirm email and click ``Submit``
- after some time you'll get a mail with the download link. Follow the instructions and download the data

6. paste the ``memories_history.html`` file in the project root (same folder as ``snapchat-downloader.py``)
for some reason snapchat doesn't let you download all your memories in a single (or multiple) zip files, but just gives you a html file (which is buggy at least for me) which lets you download all your memories.

7. run download script
```bash
python snapchat-downloader.py
```
The script then downloads all your memories
It creates the following folders/files 
- ``./snapchat_memories/``: Folder where all your memories are stored. The script automatically edits the metadata, so your files have the correct date. Files also have the prefix with the correct date and time.
Snaps with text, emojis or stickers are downloaded as zips containing all the layers. These zip files are extracted automatically.
- ``downloaded_files.json``: Json file containing some information about the downloaded files
- ``download_errors.json``: Json file containing files which had a download error

8. Trying failed downloads again
- delete the download_errors.json file
- run the download script again
- if any files do have an error again, try visiting the download link in your browser. Maybe there is no file on the other side (snapchat problem)
- if it works, try running it again

9. Adding Location Metadata
```bash
python metadata.py
```

10. deleting duplicate entries in folders with overlays
```bash
python delete-dupes.py
```

11. Reimport data (mac)
```bash
mdimport -r snapchat_memories/
```


12. correct the FileCreatedTimestamp to match the Created Timestamp
```bash
exiftool "-FileCreateDate<CreateDate" "-FileModifyDate<CreateDate" -ext mp4 -r snapchat_memories/
exiftool "-FileCreateDate<CreateDate" "-FileModifyDate<CreateDate" -ext jpg -r snapchat_memories/
```
