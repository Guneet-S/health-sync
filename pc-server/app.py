import ctypes
import os
import json
from datetime import datetime
from flask import Flask, request, jsonify
from models import db, SyncSession, Steps, HeartRate, Sleep, SpO2, Calories

try:
    ctypes.windll.kernel32.SetConsoleTitleW("HealthSync Server")
except Exception:
    pass

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_FILE = os.path.join(BASE_DIR, 'logs', 'server.log')

app = Flask(__name__)
app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///' + os.path.join(BASE_DIR, 'health.db')
app.config['SQLALCHEMY_TRACK_MODIFICATIONS'] = False

db.init_app(app)

with app.app_context():
    db.create_all()


def log(message):
    ts = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
    line = f"[{ts}] {message}"
    print(line)
    with open(LOG_FILE, 'a', encoding='utf-8') as f:
        f.write(line + '\n')


@app.route('/upload', methods=['POST'])
def upload():
    raw = request.data
    if not raw:
        return jsonify({"status": "error", "message": "No data received"}), 400

    try:
        data = json.loads(raw)
    except Exception:
        return jsonify({"status": "error", "message": "Invalid JSON"}), 400

    synced_at = datetime.now().isoformat()
    device_name = data.get('device_name', 'unknown')
    app_version = data.get('app_version', 'unknown')

    session = SyncSession(synced_at=synced_at, device_name=device_name, app_version=app_version)
    db.session.add(session)
    db.session.flush()
    sync_id = session.id

    steps_data = data.get('steps', {})
    if steps_data.get('timestamp') or steps_data.get('total') is not None:
        try:
            row = Steps(
                timestamp=steps_data.get('timestamp', synced_at),
                total_steps=steps_data.get('total', 0),
                source=steps_data.get('source', 'health_connect'),
                sync_id=sync_id
            )
            db.session.add(row)
            db.session.flush()
        except Exception:
            db.session.rollback()
            db.session.add(session)
            db.session.flush()
            sync_id = session.id

    hr_data = data.get('heart_rate', {})
    if hr_data.get('avg') is not None:
        db.session.add(HeartRate(
            timestamp=hr_data.get('timestamp', synced_at),
            avg_bpm=hr_data.get('avg', 0),
            min_bpm=hr_data.get('min', 0),
            max_bpm=hr_data.get('max', 0),
            source=hr_data.get('source', 'health_connect'),
            sync_id=sync_id
        ))

    sleep_data = data.get('sleep', {})
    if sleep_data.get('duration_hours') is not None:
        db.session.add(Sleep(
            sleep_start=sleep_data.get('start', synced_at),
            sleep_end=sleep_data.get('end', synced_at),
            duration_hours=sleep_data.get('duration_hours', 0),
            source=sleep_data.get('source', 'health_connect'),
            sync_id=sync_id
        ))

    spo2_data = data.get('spo2', {})
    if spo2_data.get('avg') is not None:
        db.session.add(SpO2(
            timestamp=spo2_data.get('timestamp', synced_at),
            avg_spo2=spo2_data.get('avg', 0),
            min_spo2=spo2_data.get('min', 0),
            source=spo2_data.get('source', 'health_connect'),
            sync_id=sync_id
        ))

    cal_data = data.get('calories', {})
    if cal_data.get('total') is not None:
        db.session.add(Calories(
            timestamp=cal_data.get('timestamp', synced_at),
            total_calories=cal_data.get('total', 0),
            source=cal_data.get('source', 'health_connect'),
            sync_id=sync_id
        ))

    db.session.commit()

    steps_val = steps_data.get('total', '?')
    hr_val = hr_data.get('avg', '?')
    cal_val = cal_data.get('total', '?')
    log(f"Saved session #{sync_id} | steps={steps_val} hr={hr_val} cal={cal_val} device={device_name}")

    return jsonify({"status": "success", "session_id": sync_id})


@app.route('/ping', methods=['GET'])
def ping():
    return jsonify({"status": "ok"})


@app.route('/stats', methods=['GET'])
def stats():
    with app.app_context():
        return jsonify({
            "sync_sessions": SyncSession.query.count(),
            "steps": Steps.query.count(),
            "heart_rate": HeartRate.query.count(),
            "sleep": Sleep.query.count(),
            "spo2": SpO2.query.count(),
            "calories": Calories.query.count(),
        })


if __name__ == '__main__':
    log("HealthSync Server starting — SQLite backend")
    log(f"DB: {os.path.join(BASE_DIR, 'health.db')}")
    log("Listening on 0.0.0.0:5000")
    app.run(host='0.0.0.0', port=5000)
