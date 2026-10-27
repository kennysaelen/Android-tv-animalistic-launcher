"""Usage: python nav.py KEY[xN] ...   e.g. python nav.py DPAD_DOWNx3 DPAD_CENTER"""
import asyncio, os, sys
from androidtvremote2 import AndroidTVRemote
D=os.path.dirname(os.path.abspath(__file__)); HOST=os.environ.get("ATV_HOST") or sys.exit("Set ATV_HOST to your TV IP address")
async def main(args):
    r=AndroidTVRemote("Animalistic Launcher tools",os.path.join(D,"cert.pem"),os.path.join(D,"key.pem"),HOST)
    await r.async_connect()
    r.add_current_app_updated_callback(lambda a: print("  app ->",a,flush=True))
    await asyncio.sleep(0.8)
    for a in args:
        k,_,n=a.upper().partition("X") if a.upper().rsplit("X",1)[-1].isdigit() else (a.upper(),"","1")
        if a.upper().rsplit("X",1)[-1].isdigit(): k,n=a.upper().rsplit("X",1)
        for _ in range(int(n)): r.send_key_command(k); await asyncio.sleep(0.5)
    await asyncio.sleep(2); print("app:",r.current_app); r.disconnect()
asyncio.run(main(sys.argv[1:]))
