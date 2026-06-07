"""
a.py

Hợp thức hoá đoạn pseudocode VRP thành một script Python an toàn để chạy
và chụp màn hình (không ném lỗi). File này giữ cấu trúc logic ở dạng stub
và dùng fallback Haversine nếu không gọi được OSRM.
"""

from math import radians, sin, cos, sqrt, atan2
from typing import List, Tuple

# --- Dữ liệu mẫu (có thể thay bằng dữ thật khi cần) ---
DEPOT: Tuple[float, float] = (21.0285, 105.8542)
DELIVERIES = [
    {"id": 1, "lat": 21.0300, "lng": 105.8540, "weight": 50, "time_window": (9 * 3600, 17 * 3600)}
]
VEHICLES = [{"id": 1, "capacity": 1000, "cost_per_km": 5000, "fixed_cost": 100000}]


def haversine(a: Tuple[float, float], b: Tuple[float, float]) -> float:
    """Return distance in kilometers between two (lat, lon) points."""
    lat1, lon1 = a
    lat2, lon2 = b
    R = 6371.0
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    a_h = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
    c = 2 * atan2(sqrt(a_h), sqrt(1 - a_h))
    return R * c


def get_data_from_osrm_api(locations: List[Tuple[float, float]]):
    """Stub for OSRM table API. Intentionally raises to exercise fallback.

    Replace implementation with a real HTTP call when network is available.
    """
    raise ConnectionError("OSRM unavailable (stub)")


def build_matrices(locations: List[Tuple[float, float]]):
    n = len(locations)
    dist = [[0.0] * n for _ in range(n)]
    time = [[0.0] * n for _ in range(n)]
    try:
        d, t = get_data_from_osrm_api(locations)
        # Expect d and t to be matrices of size n x n
        if len(d) == n and len(t) == n:
            return d, t
    except Exception:
        # Fallback: Haversine with safety multiplier and average speed
        avg_speed_kmh = 40.0
        for i in range(n):
            for j in range(n):
                km = haversine(locations[i], locations[j]) * 1.2
                dist[i][j] = round(km, 3)
                # time in seconds
                time[i][j] = int((km / avg_speed_kmh) * 3600) if avg_speed_kmh > 0 else 0
    return dist, time


def main():
    # Chuẩn bị danh sách toạ độ: depot + deliveries
    L = [DEPOT] + [(d["lat"], d["lng"]) for d in DELIVERIES]
    distance_matrix, time_matrix = build_matrices(L)

    # In ra một số thông tin để chụp màn hình, không ném lỗi
    print("Locations:", L)
    print("Distance matrix (sample):")
    for row in distance_matrix:
        print(row)
    print("Time matrix (sample):")
    for row in time_matrix:
        print(row)

    print("\nVRP stub ready — no runtime errors.")


if __name__ == "__main__":
    main()