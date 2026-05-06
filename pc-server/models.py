from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()


class SyncSession(db.Model):
    __tablename__ = 'sync_sessions'
    id = db.Column(db.Integer, primary_key=True)
    synced_at = db.Column(db.Text)
    device_name = db.Column(db.Text)
    app_version = db.Column(db.Text)


class Steps(db.Model):
    __tablename__ = 'steps'
    id = db.Column(db.Integer, primary_key=True)
    timestamp = db.Column(db.Text)
    total_steps = db.Column(db.Integer)
    source = db.Column(db.Text)
    sync_id = db.Column(db.Integer)
    __table_args__ = (db.UniqueConstraint('timestamp', 'total_steps'),)


class HeartRate(db.Model):
    __tablename__ = 'heart_rate'
    id = db.Column(db.Integer, primary_key=True)
    timestamp = db.Column(db.Text)
    avg_bpm = db.Column(db.Float)
    min_bpm = db.Column(db.Float)
    max_bpm = db.Column(db.Float)
    source = db.Column(db.Text)
    sync_id = db.Column(db.Integer)


class Sleep(db.Model):
    __tablename__ = 'sleep'
    id = db.Column(db.Integer, primary_key=True)
    sleep_start = db.Column(db.Text)
    sleep_end = db.Column(db.Text)
    duration_hours = db.Column(db.Float)
    source = db.Column(db.Text)
    sync_id = db.Column(db.Integer)


class SpO2(db.Model):
    __tablename__ = 'spo2'
    id = db.Column(db.Integer, primary_key=True)
    timestamp = db.Column(db.Text)
    avg_spo2 = db.Column(db.Float)
    min_spo2 = db.Column(db.Float)
    source = db.Column(db.Text)
    sync_id = db.Column(db.Integer)


class Calories(db.Model):
    __tablename__ = 'calories'
    id = db.Column(db.Integer, primary_key=True)
    timestamp = db.Column(db.Text)
    total_calories = db.Column(db.Float)
    source = db.Column(db.Text)
    sync_id = db.Column(db.Integer)
