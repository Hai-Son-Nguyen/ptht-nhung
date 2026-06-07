#!/usr/bin/env python3
"""Test script: Kịch bản kiểm thử độ chính xác nghiệp vụ (20 đơn hàng).

Sinh payload, gửi tới POST /api/vrp/solve, và kiểm tra các hard constraints:
- no unassigned deliveries
- vehicle capacity not exceeded
- arrival times within time windows (nếu backend trả)

Chạy: python test_vrp_small.py
"""

import requests
import random
import math
from datetime import datetime, timedelta

API = "http://localhost:8080/api/vrp/solve"
DEPOT = {"lat": 21.0285, "lng": 105.8542}
VEHICLES = [{"id": i + 1, "capacity": 500} for i in range(3)]
N = 20


def rand_point(depot, km_radius=4.0):
    lat0, lng0 = depot["lat"], depot["lng"]
    dlat = (random.uniform(-1, 1) * km_radius) / 111.0
    dlng = (random.uniform(-1, 1) * km_radius) / (111.0 * math.cos(math.radians(lat0)))
    return round(lat0 + dlat, 6), round(lng0 + dlng, 6)


def build_payload(seed=None):
    if seed is not None:
        random.seed(seed)
    deliveries = []
    now = datetime.now().replace(minute=0, second=0, microsecond=0)
    for i in range(N):
        lat, lng = rand_point(DEPOT)
        weight = random.randint(10, 60)
        # tight windows: length 1 hour, starts every 15 minutes
        start = now + timedelta(minutes=15 * i)
        end = start + timedelta(hours=1)
        # API expects minutes-from-midnight for time windows (per Delivery model)
        start_minutes = start.hour * 60 + start.minute
        end_minutes = end.hour * 60 + end.minute
        deliveries.append({
            "id": i + 1,
            "lat": lat,
            "lng": lng,
            "weight": weight,
            "timeWindowStart": start_minutes,
            "timeWindowEnd": end_minutes,
            "serviceTime": 5
        })

    payload = {"depotLat": DEPOT["lat"], "depotLng": DEPOT["lng"], "vehicles": VEHICLES, "deliveries": deliveries}
    return payload


def validate_response(payload, resp_json):
    routes = resp_json.get("routes") or resp_json.get("solution") or []
    unassigned = resp_json.get("unassigned") or resp_json.get("unassignedDeliveries") or []

    ok = True
    print("Unassigned count:", len(unassigned))
    if len(unassigned) > 0:
        ok = False

    # check capacity
    id_to_weight = {d["id"]: d["weight"] for d in payload["deliveries"]}
    for rt in routes:
        vid = rt.get("vehicleId") or rt.get("vehicle")
        stops = rt.get("stops") or rt.get("stopsList") or rt.get("steps") or []
        total_w = 0
        for s in stops:
            did = s.get("deliveryId") or s.get("id") or s.get("stopId")
            if did in id_to_weight:
                total_w += id_to_weight[did]
        cap = next((v["capacity"] for v in VEHICLES if v["id"] == vid), None)
        print(f"Route vehicle {vid}: total weight={total_w}, cap={cap}")
        if cap is not None and total_w > cap:
            print("-> CAPACITY VIOLATION on vehicle", vid)
            ok = False

    # check time windows if arrival times provided
    for rt in routes:
        stops = rt.get("stops") or []
        for s in stops:
            arr = s.get("arrival") or s.get("arrival_time") or s.get("arrivalTime")
            did = s.get("deliveryId") or s.get("id")
            if arr and did:
                tw = next((d["time_window"] for d in payload["deliveries"] if d["id"] == did), None)
                if tw and not (tw[0] - 1 <= arr <= tw[1] + 1):
                    print("-> TIME WINDOW VIOLATION delivery", did, "arr", arr, "tw", tw)
                    ok = False

    return ok, routes, unassigned


def run_once(seed=None):
    payload = build_payload(seed)
    try:
        r = requests.post(API, json=payload, timeout=60)
        r.raise_for_status()
        resp = r.json()
    except Exception as e:
        print("Request failed:", e)
        return False

    ok, routes, unassigned = validate_response(payload, resp)
    print("Routes returned:", len(routes))
    # Basic visual-ish metrics
    total_dist = sum((r.get("distance", 0) or 0) for r in routes)
    print("Total reported distance:", total_dist)
    if ok:
        print("TEST PASSED: hard constraints OK")
    else:
        print("TEST FAILED: see above messages")
    return ok


if __name__ == "__main__":
    print("Running VRP small scenario (20 deliveries, 3 vehicles)")
    success = run_once(seed=42)
    print("Finished: ", "OK" if success else "FAILED")
