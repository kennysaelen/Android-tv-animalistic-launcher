import sys
import asyncio, os
from androidtvremote2 import AndroidTVRemote
D=os.path.dirname(os.path.abspath(__file__)); HOST=os.environ.get("ATV_HOST") or sys.exit("Set ATV_HOST to your TV IP address")
async def main():
    r=AndroidTVRemote("Animalistic Launcher tools",os.path.join(D,"cert.pem"),os.path.join(D,"key.pem"),HOST)
    await r.async_generate_cert_if_missing()
    await r.async_start_pairing()
    code=input("Enter the code shown on the TV: ").strip()
    await r.async_finish_pairing(code)
    await r.async_connect()
    print("Paired and connected:",r.device_info)
    r.disconnect()
asyncio.run(main())
