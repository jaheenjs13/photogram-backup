import os
import argparse
import asyncio
from pyrogram import Client
from pyrogram.types import Message

async def progress(current, total):
    print(f"{current * 100 / total:.1f}%")

async def upload_file(api_id, api_hash, bot_token, chat_id, file_path, thread_id=None):
    async with Client("my_bot", api_id=api_id, api_hash=api_hash, bot_token=bot_token, in_memory=True) as app:
        print(f"Uploading {file_path} to {chat_id}...")
        
        # Decide whether to send as document or other type based on extension if needed.
        # For backup purposes, send_document is usually best to preserve generic file types.
        try:
            # Check if file exists
            if not os.path.exists(file_path):
                print(f"Error: File not found at {file_path}")
                return

            await app.send_document(
                chat_id=chat_id,
                document=file_path,
                reply_to_message_id=int(thread_id) if thread_id else None,
                progress=progress
            )
            print("\nUpload complete!")
        except Exception as e:
            print(f"\nError during upload: {e}")

def main():
    parser = argparse.ArgumentParser(description="Upload large files to Telegram via MTProto")
    parser.add_argument("--api_id", required=True, help="Telegram API ID")
    parser.add_argument("--api_hash", required=True, help="Telegram API Hash")
    parser.add_argument("--bot_token", required=True, help="Telegram Bot Token")
    parser.add_argument("--chat_id", required=True, help="Target Chat ID")
    parser.add_argument("--file", required=True, help="Path to file to upload")
    parser.add_argument("--thread_id", help="Message Thread ID (Topic ID) for forums", default=None)

    args = parser.parse_args()

    asyncio.run(upload_file(
        args.api_id,
        args.api_hash,
        args.bot_token,
        args.chat_id,
        args.file,
        args.thread_id
    ))

if __name__ == "__main__":
    main()
