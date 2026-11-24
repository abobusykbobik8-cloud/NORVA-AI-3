"""Edit an encrypted NØRVA Cross Sync archive on a PC. Requires Python 3 and cryptography."""
import argparse
import getpass
import hashlib
import json
import os
import pathlib
import time
import uuid
import http.client
import base64
from urllib.parse import urlsplit
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b"NORVA14!"
LIMIT = 8 * 1024 * 1024

def key(password, salt):
    return hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"), salt, 600000, 32)

def decode(data, password):
    if not data.startswith(MAGIC) or not 52 <= len(data) <= LIMIT + 52:
        raise ValueError("Invalid archive")
    obj = json.loads(AESGCM(key(password, data[8:24])).decrypt(data[24:36], data[36:], MAGIC))
    if obj["version"] != 1:
        raise ValueError("Unsupported format")
    return obj

def encode(obj, password):
    raw = json.dumps(obj, ensure_ascii=False).encode()
    if len(password) < 12 or len(raw) > LIMIT:
        raise ValueError("Passphrase must have 12+ characters and archive must fit in 8 MiB")
    salt, nonce = os.urandom(16), os.urandom(12)
    return MAGIC + salt + nonce + AESGCM(key(password, salt)).encrypt(nonce, raw, MAGIC)

def dav_request(url, method, user, secret, payload=None, etag=None):
    parsed = urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        raise ValueError("Use an HTTPS file URL without credentials or query parameters")
    headers = {}
    if user:
        auth = base64.b64encode((user + ":" + secret).encode()).decode()
        headers["Authorization"] = "Basic " + auth
    if method == "PUT":
        headers["If-Match" if etag else "If-None-Match"] = etag or "*"
        headers["Content-Type"] = "application/octet-stream"
    conn = http.client.HTTPSConnection(parsed.hostname, parsed.port, timeout=25)
    try:
        conn.request(method, parsed.path or "/", body=payload, headers=headers)
        response = conn.getresponse()
        if response.status == 412:
            raise ValueError("Remote archive changed; repeat with a fresh read")
        if response.status not in (200, 201, 204):
            raise ValueError("WebDAV HTTP " + str(response.status))
        tag = response.getheader("ETag", "")
        if method == "GET" and not (tag.startswith('"') and tag.endswith('"')):
            raise ValueError("Server must support strong ETags")
        data = response.read(LIMIT + 53)
        if len(data) > LIMIT + 52:
            raise ValueError("Archive too large")
        return data, tag
    finally:
        conn.close()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("archive", type=pathlib.Path, nargs="?")
    p.add_argument("--dav", help="HTTPS URL of the encrypted shared archive")
    p.add_argument("--user", default="", help="WebDAV username")
    p.add_argument("--output", type=pathlib.Path, help="New encrypted archive after changes")
    p.add_argument("--chat", help="Existing chat UUID; without it, start a new chat")
    p.add_argument("--add", action="store_true", help="Append a user message read interactively")
    p.add_argument("--mode", choices=["FAST","NORMAL","DEEP"])
    args = p.parse_args()
    password = getpass.getpass("Archive passphrase: ")
    if bool(args.dav) == bool(args.archive):
        p.error("Choose one archive file or --dav URL")
    secret = getpass.getpass("WebDAV app password: ") if args.dav and args.user else ""
    if args.dav:
        data, etag = dav_request(args.dav, "GET", args.user, secret)
    else:
        if args.archive.stat().st_size > LIMIT + 52:
            p.error("Archive too large")
        data = args.archive.read_bytes()
    obj = decode(data, password)
    if not args.add and not args.mode:
        for chat in obj["chats"]:
            print(chat["id"], chat["title"])
            if args.chat == chat["id"]:
                for msg in chat["messages"]:
                    print(msg["role"], msg["text"])
        return
    if not args.dav and args.output is None:
        p.error("Choose a fresh --output file")
    if args.mode:
        obj["settings"]["mode"] = args.mode
    if args.add:
        now = int(time.time()*1000)
        text = input("Message: ").strip()
        if not text:
            p.error("Empty message")
        if args.chat:
            chat = next((c for c in obj["chats"] if c["id"]==args.chat), None)
            if chat is None:
                p.error("Unknown chat")
        else:
            chat = dict(id=str(uuid.uuid4()),title=text[:48],time=now,messages=[])
            obj["chats"].append(chat)
        chat["messages"].append(dict(id=str(uuid.uuid4()),role="USER",text=text,time=now))
    payload = encode(obj,password)
    if args.dav:
        dav_request(args.dav, "PUT", args.user, secret, payload, etag)
        return
    with args.output.open("xb") as f:
        f.write(payload)

if __name__ == "__main__":
    main()
