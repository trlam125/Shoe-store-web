import io
import ipaddress
import os
import socket
from functools import lru_cache
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlparse

import numpy as np
import pandas as pd
import psycopg
import requests
import torch
from dotenv import load_dotenv
from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from PIL import Image
from sklearn.cluster import KMeans
from sklearn.ensemble import RandomForestRegressor
from sklearn.preprocessing import StandardScaler
from torchvision.models import ResNet18_Weights, resnet18

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")

def resolve_database_url() -> str:
    configured = os.getenv("AI_DATABASE_URL") or os.getenv("DATABASE_URL")
    if configured:
        return configured.removeprefix("jdbc:")
    username = os.getenv("DB_USERNAME", "postgres")
    password = os.getenv("DB_PASSWORD", "120505")
    host = os.getenv("DB_HOST", "localhost")
    port = os.getenv("DB_PORT", "5432")
    database = os.getenv("DB_NAME", "lshoe_store")
    return f"postgresql://{username}:{password}@{host}:{port}/{database}"


DATABASE_URL = resolve_database_url()
ALLOWED_ORIGINS = [
    origin.strip()
    for origin in os.getenv(
        "AI_ALLOWED_ORIGINS", "http://localhost:8081,http://127.0.0.1:8081"
    ).split(",")
    if origin.strip()
]
MAX_UPLOAD_BYTES = int(os.getenv("AI_MAX_UPLOAD_BYTES", str(10 * 1024 * 1024)))
MAX_REMOTE_IMAGE_BYTES = int(
    os.getenv("AI_MAX_REMOTE_IMAGE_BYTES", str(12 * 1024 * 1024))
)
ALLOWED_IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp"}
Image.MAX_IMAGE_PIXELS = int(os.getenv("AI_MAX_IMAGE_PIXELS", "25000000"))

app = FastAPI(title="LSHOE ML/DL Service", version="1.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type"],
)

http_session = requests.Session()
http_session.headers.update({"User-Agent": "LSHOE-ImageSearch/1.1"})


def query_df(sql: str, params: tuple[Any, ...] = ()) -> pd.DataFrame:
    with psycopg.connect(DATABASE_URL) as conn:
        with conn.cursor() as cursor:
            cursor.execute(sql, params)
            if cursor.description is None:
                return pd.DataFrame()
            columns = [column.name for column in cursor.description]
            return pd.DataFrame(cursor.fetchall(), columns=columns)


@app.get("/health")
def health():
    try:
        with psycopg.connect(DATABASE_URL) as conn:
            conn.execute("SELECT 1")
        return {"status": "ok", "database": "connected"}
    except Exception:
        return JSONResponse(
            status_code=503,
            content={"status": "degraded", "database": "unavailable"},
        )


@app.get("/ml/customer-segments")
def customer_segments() -> dict[str, Any]:
    sql = """
        SELECT u.id AS user_id, u.full_name,
               COALESCE(EXTRACT(DAY FROM (CURRENT_TIMESTAMP - MAX(o.completed_at))), 999) AS recency,
               COUNT(DISTINCT o.id) AS frequency,
               COALESCE(SUM(o.total), 0) AS monetary
        FROM users u
        LEFT JOIN orders o ON o.user_id = u.id AND o.status = 'HOAN_THANH'
        WHERE u.role <> 'ROLE_ADMIN'
        GROUP BY u.id, u.full_name
        ORDER BY u.id
    """
    df = query_df(sql)
    if df.empty:
        return {
            "algorithm": "RFM + KMeans",
            "segments": [],
            "message": "Chưa có khách hàng để phân nhóm.",
        }

    features = df[["recency", "frequency", "monetary"]].astype(float)
    cluster_count = min(4, len(features.drop_duplicates()))
    if cluster_count <= 1:
        labels = np.zeros(len(df), dtype=int)
    else:
        scaled = StandardScaler().fit_transform(features)
        labels = KMeans(
            n_clusters=cluster_count, random_state=42, n_init=10
        ).fit_predict(scaled)
    df["cluster"] = labels

    summaries = []
    for cluster, group in df.groupby("cluster"):
        avg_r = float(group["recency"].mean())
        avg_f = float(group["frequency"].mean())
        avg_m = float(group["monetary"].mean())
        if avg_f == 0:
            name = "Chưa phát sinh mua hàng"
        elif avg_f >= max(2, float(df["frequency"].mean())) and avg_m >= float(
            df["monetary"].mean()
        ):
            name = "Khách hàng giá trị cao"
        elif avg_r <= 30:
            name = "Khách hàng mới/đang hoạt động"
        elif avg_r >= 90:
            name = "Có nguy cơ rời bỏ"
        else:
            name = "Khách hàng tiềm năng"
        summaries.append(
            {
                "cluster": int(cluster),
                "name": name,
                "customers": int(len(group)),
                "avgRecencyDays": round(avg_r, 1),
                "avgFrequency": round(avg_f, 1),
                "avgMonetary": round(avg_m, 0),
            }
        )
    return {"algorithm": "RFM + KMeans", "segments": summaries}


def stock_rule_forecast(days: int) -> dict[str, Any]:
    stock = query_df(
        "SELECT id AS product_id, name, stock "
        "FROM product WHERE active = true ORDER BY stock ASC LIMIT 10"
    )
    fallback = [
        {
            "productId": int(row.product_id),
            "productName": row.name,
            "predictedUnits": 0,
            "recommendedRestock": max(0, 10 - int(row.stock)),
            "method": "stock-rule-fallback",
        }
        for row in stock.itertuples()
    ]
    return {
        "algorithm": "RandomForestRegressor",
        "forecastDays": days,
        "predictions": fallback,
        "message": "Chưa đủ dữ liệu lịch sử; đang dùng quy tắc tồn kho dự phòng.",
    }


@app.get("/ml/sales-forecast")
def sales_forecast(days: int = Query(default=7, ge=1, le=30)) -> dict[str, Any]:
    sql = """
        SELECT p.id AS product_id, p.name, p.stock,
               DATE(o.completed_at) AS sale_date,
               SUM(oi.quantity) AS quantity
        FROM order_item oi
        JOIN orders o ON o.id = oi.order_id
        JOIN product p ON p.id = oi.product_id
        WHERE o.status = 'HOAN_THANH'
          AND o.completed_at IS NOT NULL
          AND p.active = true
        GROUP BY p.id, p.name, p.stock, DATE(o.completed_at)
        ORDER BY p.id, sale_date
    """
    df = query_df(sql)
    if df.empty:
        return stock_rule_forecast(days)

    df["sale_date"] = pd.to_datetime(df["sale_date"])
    history_start = df["sale_date"].min()
    history_end = df["sale_date"].max()
    history_dates = pd.date_range(history_start, history_end, freq="D")
    if len(history_dates) < 3:
        return stock_rule_forecast(days)

    results = []
    for product_id, group in df.groupby("product_id"):
        daily_sales = (
            group.groupby("sale_date")["quantity"]
            .sum()
            .reindex(history_dates, fill_value=0)
            .astype(float)
        )
        x = pd.DataFrame({"day_index": np.arange(len(daily_sales))})
        model = RandomForestRegressor(
            n_estimators=120, random_state=42, min_samples_leaf=1
        )
        model.fit(x, daily_sales.to_numpy())
        future = pd.DataFrame(
            {"day_index": range(len(daily_sales), len(daily_sales) + days)}
        )
        predicted = float(model.predict(future).sum())
        current_stock = int(group.iloc[0]["stock"])
        results.append(
            {
                "productId": int(product_id),
                "productName": str(group.iloc[0]["name"]),
                "predictedUnits": round(max(0, predicted), 1),
                "recommendedRestock": max(
                    0, int(np.ceil(max(0, predicted) * 1.2)) - current_stock
                ),
                "method": "random-forest",
            }
        )
    results.sort(key=lambda item: item["predictedUnits"], reverse=True)
    return {
        "algorithm": "RandomForestRegressor",
        "forecastDays": days,
        "predictions": results[:10],
    }


@lru_cache(maxsize=1)
def vision_model():
    weights = ResNet18_Weights.DEFAULT
    model = resnet18(weights=weights)
    model.fc = torch.nn.Identity()
    model.eval()
    return model, weights.transforms()


def embedding(image: Image.Image) -> np.ndarray:
    model, preprocess = vision_model()
    tensor = preprocess(image.convert("RGB")).unsqueeze(0)
    with torch.inference_mode():
        vector = model(tensor).squeeze(0).numpy()
    return vector / (np.linalg.norm(vector) + 1e-12)


def open_image(payload: bytes) -> Image.Image:
    try:
        image = Image.open(io.BytesIO(payload))
        image.load()
        return image.convert("RGB")
    except Exception as exc:
        raise ValueError("Dữ liệu không phải ảnh hợp lệ.") from exc


def is_public_http_url(url: str) -> bool:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return False
    try:
        addresses = {
            item[4][0]
            for item in socket.getaddrinfo(parsed.hostname, parsed.port or 443)
        }
        return bool(addresses) and all(
            not (
                ipaddress.ip_address(address).is_private
                or ipaddress.ip_address(address).is_loopback
                or ipaddress.ip_address(address).is_link_local
                or ipaddress.ip_address(address).is_reserved
                or ipaddress.ip_address(address).is_multicast
                or ipaddress.ip_address(address).is_unspecified
            )
            for address in addresses
        )
    except (OSError, ValueError):
        return False


def download_image(url: str) -> Image.Image:
    current_url = url
    for _ in range(4):
        if not is_public_http_url(current_url):
            raise ValueError("URL ảnh không hợp lệ hoặc không an toàn.")

        response = http_session.get(
            current_url, timeout=(3, 8), stream=True, allow_redirects=False
        )
        if response.is_redirect or response.is_permanent_redirect:
            location = response.headers.get("Location")
            response.close()
            if not location:
                raise ValueError("URL ảnh chuyển hướng không hợp lệ.")
            current_url = urljoin(current_url, location)
            continue

        with response:
            response.raise_for_status()
            content_type = response.headers.get("Content-Type", "").split(";", 1)[0]
            if content_type not in ALLOWED_IMAGE_TYPES:
                raise ValueError("URL không trả về định dạng ảnh được hỗ trợ.")
            content_length = int(response.headers.get("Content-Length", "0") or 0)
            if content_length > MAX_REMOTE_IMAGE_BYTES:
                raise ValueError("Ảnh sản phẩm quá lớn.")

            payload = bytearray()
            for chunk in response.iter_content(chunk_size=64 * 1024):
                payload.extend(chunk)
                if len(payload) > MAX_REMOTE_IMAGE_BYTES:
                    raise ValueError("Ảnh sản phẩm quá lớn.")
        return open_image(bytes(payload))
    raise ValueError("URL ảnh chuyển hướng quá nhiều lần.")


@lru_cache(maxsize=512)
def product_embedding(image_url: str) -> np.ndarray:
    return embedding(download_image(image_url))


@app.post("/dl/image-search")
async def image_search(
    file: UploadFile = File(...), limit: int = Query(default=6, ge=1, le=12)
) -> dict[str, Any]:
    if file.content_type not in ALLOWED_IMAGE_TYPES:
        raise HTTPException(
            status_code=415,
            detail="Chỉ hỗ trợ ảnh JPEG, PNG hoặc WebP.",
        )

    payload = await file.read(MAX_UPLOAD_BYTES + 1)
    if len(payload) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Ảnh tải lên vượt quá 10 MB.")
    try:
        query_vector = embedding(open_image(payload))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail="Không tải được mô hình ResNet18. Hãy kiểm tra kết nối mạng.",
        ) from exc
    finally:
        await file.close()

    products = query_df(
        "SELECT id, name, brand, image_url "
        "FROM product WHERE active = true AND image_url IS NOT NULL"
    )
    matches = []
    for row in products.itertuples():
        try:
            product_vector = product_embedding(str(row.image_url))
            score = float(np.dot(query_vector, product_vector))
            matches.append(
                {
                    "productId": int(row.id),
                    "name": row.name,
                    "brand": row.brand,
                    "imageUrl": row.image_url,
                    "similarity": round(score, 4),
                }
            )
        except Exception:
            continue
    matches.sort(key=lambda item: item["similarity"], reverse=True)
    return {"model": "ResNet18 pretrained", "matches": matches[:limit]}
