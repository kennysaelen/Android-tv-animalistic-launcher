"""Usage: python remote.py KEY [KEY ...] | python remote.py --app <url_or_package>
Keys: POWER HOME BACK DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT DPAD_CENTER VOLUME_UP VOLUME_DOWN VOLUME_MUTE MEDIA_PLAY_PAUSE ..."""
import asyncio, os, sys
from androidtvremote2 import AndroidTVRemote
D=os.path.dirname(os.path.abspath(__file__)); HOST=os.environ.get("ATV_HOST") or sys.exit("Set ATV_HOST to your TV IP address")
async def main(args):
    if not args: print(__doc__); return
    r=AndroidTVRemote("Animalistic Launcher tools",os.path.join(D,"cert.pem"),os.path.join(D,"key.pem"),HOST)
    await r.async_connect()
    if args[0]=="--app": r.send_launch_app_command(args[1])
    else:
        for k in args: r.send_key_command(k.upper()); await asyncio.sleep(0.3)
    await asyncio.sleep(0.5)
    print("on:",r.is_on,"app:",r.current_app,"volume:",r.volume_info)
    r.disconnect()
asyncio.run(main(sys.argv[1:]))
