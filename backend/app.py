"""Single-instance, bounded, anonymous public-video extraction service."""
import asyncio
import hashlib
import json
import os
import re
import shutil
import sys
import time
import uuid
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlsplit
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import FileResponse

ROOT = Path(os.getenv('MEDIA_ROOT', '/tmp/black-hole-media'))
ROOT.mkdir(parents=True, exist_ok=True)
JOBS = {}
JOB_KEYS = {}
RATES = defaultdict(deque)
TASKS = set()
LIMIT = asyncio.Semaphore(2)
TTL = 1800
MAX_CACHE = 4 * 1024**3


def disk_used():
    return sum(p.stat().st_size for p in ROOT.rglob('*') if p.is_file())


async def extract(job_id, url):
    job = JOBS[job_id]
    folder = ROOT / job_id
    process = None
    async with LIMIT:
        try:
            if disk_used() > MAX_CACHE or shutil.disk_usage(ROOT).free < 2 * 1024**3:
                raise RuntimeError('Capacity reached')
            folder.mkdir()
            process = await asyncio.create_subprocess_exec(sys.executable, str(Path(__file__).with_name('worker.py')), url, str(folder), stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
            started = time.monotonic()
            while process.returncode is None:
                await asyncio.sleep(1)
                if time.monotonic() - started > 300 or disk_used() > MAX_CACHE:
                    raise RuntimeError('Extraction limit reached')
            if process.returncode != 0:
                error_file = folder / 'error.json'
                if error_file.is_file():
                    error = json.loads(error_file.read_text()).get('error')
                    if error:
                        raise RuntimeError(error)
                raise RuntimeError('VIDEO UNAVAILABLE, RESTRICTED OR NOT SUPPORTED')
            job.update(json.loads((folder / 'metadata.json').read_text()))
            job['status'] = 'ready'
        except asyncio.CancelledError:
            if process is not None and process.returncode is None:
                process.kill()
                await process.wait()
            shutil.rmtree(folder, ignore_errors=True)
            raise
        except Exception as exc:
            if process is not None and process.returncode is None:
                process.kill()
                await process.wait()
            shutil.rmtree(folder, ignore_errors=True)
            message = str(exc)
            allowed = {
                'VIDEO UNAVAILABLE, RESTRICTED OR NOT SUPPORTED',
                'VIDEO REQUIRES LOGIN OR IS PRIVATE',
                'VIDEO EXTRACTION TIMED OUT',
                'VIDEO EXCEEDS THE 1 GB SERVER LIMIT',
                'NO COMPATIBLE H.264/AAC MP4 AVAILABLE',
                'DOWNLOADED VIDEO HAS NO AUDIO',
                'DOWNLOADED FILE IS NOT A PLAYABLE MP4',
                'Capacity reached',
                'Extraction limit reached',
            }
            job.update(status='failed', error=message if message in allowed else 'VIDEO UNAVAILABLE, RESTRICTED OR NOT SUPPORTED')
        finally:
            job['expires'] = time.monotonic() + TTL


async def cleanup():
    while True:
        await asyncio.sleep(60)
        now = time.monotonic()
        for key, job in list(JOBS.items()):
            if job['status'] != 'processing' and job['expires'] < now:
                JOBS.pop(key, None)
                if JOB_KEYS.get(job['key']) == key:
                    JOB_KEYS.pop(job['key'], None)
                shutil.rmtree(ROOT / key, ignore_errors=True)
        for key, rate in list(RATES.items()):
            if not rate or rate[-1] < now - 60:
                RATES.pop(key, None)


@asynccontextmanager
async def lifespan(app):
    # This service deliberately runs one uvicorn worker; jobs are ephemeral.
    for path in ROOT.iterdir():
        if path.is_dir() and re.fullmatch('[a-f0-9]{32}', path.name):
            shutil.rmtree(path, ignore_errors=True)
    task = asyncio.create_task(cleanup())
    yield
    task.cancel()
    for item in list(TASKS):
        item.cancel()
    await asyncio.gather(task, *list(TASKS), return_exceptions=True)


app = FastAPI(title='BLACK HOLE extraction', docs_url=None, redoc_url=None, lifespan=lifespan)


@app.middleware('http')
async def no_cache(request, call_next):
    response = await call_next(request)
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    return response


@app.get('/health')
async def health():
    return {'status': 'ok', 'service': 'black-hole', 'api_version': 1}


@app.get('/v1/jobs')
async def create_job(url: str, request: Request):
    if len(url) > 4096:
        raise HTTPException(400, 'URL too long')
    try:
        u = urlsplit(url)
        valid = u.scheme == 'https' and u.hostname and not u.username and not u.password and u.port in (None,443)
    except ValueError:
        valid = False
    if not valid:
        raise HTTPException(400, 'Public HTTPS URL required')
    key = hashlib.sha256(url.encode('utf-8')).hexdigest()
    existing_id = JOB_KEYS.get(key)
    existing = JOBS.get(existing_id) if existing_id else None
    if existing and (existing['status'] == 'processing' or (existing['status'] == 'ready' and existing['expires'] >= time.monotonic())):
        return {'id': existing_id}
    # Trust proxy headers only when uvicorn is configured with your known proxy IPs.
    ip = request.client.host if request.client else 'unknown'
    now = time.monotonic()
    rate = RATES[ip]
    while rate and rate[0] < now - 60:
        rate.popleft()
    if len(rate) >= 5 or sum(j['status'] == 'processing' for j in JOBS.values()) >= 8 or len(JOBS) >= 300:
        raise HTTPException(429, 'Try again later')
    rate.append(now)
    job_id = uuid.uuid4().hex
    JOBS[job_id] = {'status': 'processing', 'expires': now + TTL, 'key': key}
    JOB_KEYS[key] = job_id
    task = asyncio.create_task(extract(job_id, url))
    TASKS.add(task)
    task.add_done_callback(TASKS.discard)
    return {'id': job_id}


def get_job(job_id):
    if not re.fullmatch('[a-f0-9]{32}', job_id) or job_id not in JOBS:
        raise HTTPException(404, 'Expired job')
    job = JOBS[job_id]
    if job['status'] != 'processing' and job['expires'] < time.monotonic():
        raise HTTPException(404, 'Expired job')
    return job


@app.get('/v1/jobs/{job_id}')
async def status(job_id: str):
    job = get_job(job_id)
    return {key: value for key, value in job.items() if key not in ('expires', 'key')}


@app.get('/v1/media/{job_id}')
async def media(job_id: str):
    job = get_job(job_id)
    if job['status'] != 'ready':
        raise HTTPException(409, 'Not ready')
    job['expires'] = time.monotonic() + TTL
    return FileResponse(ROOT / job_id / 'video.mp4', media_type='video/mp4', filename='video.mp4')
