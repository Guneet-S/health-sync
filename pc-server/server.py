from flask import Flask, request, jsonify
import os
import json
import hashlib
import ctypes
from datetime import datetime

# Set console window title
try:
    ctypes.windll.kernel32.SetConsoleTitleW("HealthSync Server")
except Exception:
    pass

app = Flask(__name__)

SAVE_PATH = r"C:\claude-agents\health-sync\data"

if not os.path.exists(SAVE_PATH):
    os.makedirs(SAVE_PATH)

# In-memory dedup set (timestamp + steps + hr as key)
seen_hashes = set()

def payload_hash(data: dict) -> str:
    key = f"{data.get('timestamp','')}-{data.get('steps',{}).get('total',0)}-{data.get('heart_rate',{}).get('avg',0)}-{data.get('calories',{}).get('total',0)}"
    return hashlib.md5(key.encode()).hexdigest()

@app.route('/upload', methods=['POST'])
def upload():
    raw = request.data
    if not raw:
        return jsonify({"status": "error", "message": "No data received"}), 400

    try:
        data = json.loads(raw)
    except Exception:
        return jsonify({"status": "error", "message": "Invalid JSON"}), 400

    h = payload_hash(data)
    if h in seen_hashes:
        print(f"[{datetime.now()}] Duplicate skipped (hash={h[:8]})")
        return jsonify({"status": "duplicate", "message": "Already received this payload"})

    seen_hashes.add(h)

    filename = f"data_{datetime.now().strftime('%Y-%m-%d_%H-%M-%S')}.json"
    filepath = os.path.join(SAVE_PATH, filename)
    with open(filepath, 'w') as f:
        json.dump(data, f, indent=2)

    steps = data.get('steps', {}).get('total', '?')
    hr = data.get('heart_rate', {}).get('avg', '?')
    sleep = data.get('sleep', {}).get('duration_hours', '?')
    cal = data.get('calories', {}).get('total', '?')
    print(f"[{datetime.now()}] Saved: {filename} | steps={steps} hr={hr} sleep={sleep}h cal={cal}kcal")

    return jsonify({"status": "success", "file": filename})

@app.route('/ping', methods=['GET'])
def ping():
    return jsonify({"status": "ok"})

if __name__ == '__main__':
    print(f"[HealthSync Server] Saving to: {SAVE_PATH}")
    print("[HealthSync Server] Listening on 0.0.0.0:5000")
    app.run(host='0.0.0.0', port=5000)
