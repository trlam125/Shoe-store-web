import hmac
import io
import ipaddress
import math
import os
import socket
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable
from urllib.parse import quote, urljoin, urlparse, urlsplit, urlunsplit

import numpy as np
import pandas as pd
import psycopg
import requests
import torch
from dotenv import dotenv_values
from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from PIL import Image
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler
from torchvision.models import ResNet18_Weights, resnet18

BASE_DIR = Path(__file__).resolve().parent.parent
PROJECT_ROOT = BASE_DIR.parent

# Keep one shared project configuration at the repository root. A service-local
# ai-service/.env may override the shared file, while explicit process variables
# (for example AI_RELOAD=false from the Java auto-starter) retain highest priority.
file_environment = {
    **dotenv_values(PROJECT_ROOT / ".env"),
    **dotenv_values(BASE_DIR / ".env"),
}
for key, value in file_environment.items():
    if value is not None:
        os.environ.setdefault(key, value)


def resolve_database_url() -> str:
    """Build a psycopg URL from the same root .env values used by Spring Boot.

    Spring commonly stores a JDBC URL without credentials in DATABASE_URL and
    keeps DB_USERNAME/DB_PASSWORD separately. Psycopg expects those credentials
    in the PostgreSQL URI (or as separate arguments), so inject them only when
    the configured URI does not already contain a username.
    """
    configured = (os.getenv("AI_DATABASE_URL") or os.getenv("DATABASE_URL") or "").strip()
    username = os.getenv("DB_USERNAME", "postgres")
    password = os.getenv("DB_PASSWORD", "postgres")

    if configured:
        database_url = configured.removeprefix("jdbc:")
        parsed = urlsplit(database_url)
        if parsed.scheme not in {"postgresql", "postgres"}:
            raise RuntimeError(
                "AI_DATABASE_URL/DATABASE_URL must use the postgresql:// or postgres:// scheme."
            )
        if parsed.username is not None:
            return database_url
        if not parsed.hostname:
            raise RuntimeError("Database URL is missing a host name.")

        host = parsed.hostname
        if ":" in host and not host.startswith("["):
            host = f"[{host}]"
        port = f":{parsed.port}" if parsed.port is not None else ""
        credentials = quote(username, safe="")
        if password:
            credentials += f":{quote(password, safe='')}"
        netloc = f"{credentials}@{host}{port}"
        return urlunsplit((parsed.scheme, netloc, parsed.path, parsed.query, parsed.fragment))

    host = os.getenv("DB_HOST", "localhost")
    port = os.getenv("DB_PORT", "5432")
    database = os.getenv("DB_NAME", "lshoe_store")
    credentials = quote(username, safe="")
    if password:
        credentials += f":{quote(password, safe='')}"
    return f"postgresql://{credentials}@{host}:{port}/{database}"


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
AI_INTERNAL_API_KEY = os.getenv("AI_INTERNAL_API_KEY", "").strip()
STORE_BASE_URL = os.getenv("AI_STORE_BASE_URL", "http://127.0.0.1:8081").rstrip("/")
TRUSTED_IMAGE_ORIGINS = {
    origin.rstrip("/")
    for origin in os.getenv("AI_TRUSTED_IMAGE_ORIGINS", STORE_BASE_URL).split(",")
    if origin.strip()
}
MIN_SHOE_CONFIDENCE = float(os.getenv("AI_MIN_SHOE_CONFIDENCE", "0.03"))
MIN_IMAGE_SIMILARITY = float(os.getenv("AI_MIN_IMAGE_SIMILARITY", "0.68"))
MAX_FORECAST_HISTORY_DAYS = int(os.getenv("AI_FORECAST_HISTORY_DAYS", "365"))
FORECAST_SERVICE_Z = float(os.getenv("AI_FORECAST_SERVICE_Z", "1.28"))
Image.MAX_IMAGE_PIXELS = int(os.getenv("AI_MAX_IMAGE_PIXELS", "25000000"))

app = FastAPI(title="LSHOE ML/DL Service", version="1.2.1")
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "X-Internal-API-Key"],
)

http_session = requests.Session()
http_session.headers.update({"User-Agent": "LSHOE-ImageSearch/1.2"})


def verify_internal_request(
    request: Request, x_internal_api_key: str | None = Header(default=None)
) -> None:
    if AI_INTERNAL_API_KEY:
        if not x_internal_api_key or not hmac.compare_digest(
            x_internal_api_key, AI_INTERNAL_API_KEY
        ):
            raise HTTPException(status_code=401, detail="Invalid internal API key.")
        return
    client_host = request.client.host if request.client else ""
    try:
        address = ipaddress.ip_address(client_host)
        if not address.is_loopback:
            raise HTTPException(
                status_code=403,
                detail="AI service only accepts local requests when no API key is configured.",
            )
    except ValueError as exc:
        raise HTTPException(status_code=403, detail="Invalid client address.") from exc


def query_df(sql: str, params: tuple[Any, ...] = ()) -> pd.DataFrame:
    with psycopg.connect(DATABASE_URL) as conn:
        with conn.cursor() as cursor:
            cursor.execute(sql, params)
            if cursor.description is None:
                return pd.DataFrame()
            columns = [column.name for column in cursor.description]
            return pd.DataFrame(cursor.fetchall(), columns=columns)


@app.get("/live")
def live():
    return {"status": "ok", "service": "running", "version": app.version}


def database_readiness_response():
    try:
        with psycopg.connect(DATABASE_URL) as conn:
            conn.execute("SELECT 1")
        return {"status": "ok", "database": "connected", "version": app.version}
    except Exception:
        return JSONResponse(
            status_code=503,
            content={"status": "degraded", "database": "unavailable", "version": app.version},
        )


@app.get("/ready")
def ready():
    return database_readiness_response()


@app.get("/health")
def health():
    # Giữ endpoint cũ để tương thích; /live dùng kiểm tra process, /ready dùng kiểm tra sẵn sàng.
    return database_readiness_response()


# ---------------------------------------------------------------------------
# RFM customer segmentation
# ---------------------------------------------------------------------------

def _segment_summary(group: pd.DataFrame, cluster: int, name: str) -> dict[str, Any]:
    return {
        "cluster": int(cluster),
        "name": name,
        "customers": int(len(group)),
        "avgRecencyDays": round(float(group["recency"].mean()), 1),
        "avgFrequency": round(float(group["frequency"].mean()), 1),
        "avgMonetary": round(float(group["monetary"].mean()), 0),
    }


def _rule_segment_name(row: pd.Series, buyers: pd.DataFrame) -> str:
    recency_median = float(buyers["recency"].median())
    frequency_q75 = float(buyers["frequency"].quantile(0.75))
    monetary_q75 = float(buyers["monetary"].quantile(0.75))
    if row["frequency"] >= max(2.0, frequency_q75) and row["monetary"] >= monetary_q75:
        return "Khách hàng giá trị cao"
    if row["recency"] >= max(90.0, recency_median * 1.5):
        return "Có nguy cơ rời bỏ"
    if row["recency"] <= min(30.0, recency_median):
        return "Khách hàng đang hoạt động"
    return "Khách hàng tiềm năng"


def _unique_cluster_names(buyers: pd.DataFrame) -> dict[int, str]:
    centers = buyers.groupby("cluster").agg(
        recency=("recency", "mean"),
        frequency=("frequency", "mean"),
        monetary=("monetary", "mean"),
    )
    if centers.empty:
        return {}

    normalized = centers.copy()
    for column in ("recency", "frequency", "monetary"):
        std = float(normalized[column].std(ddof=0))
        normalized[column] = 0.0 if std < 1e-12 else (
            normalized[column] - normalized[column].mean()
        ) / std
    normalized["value"] = -normalized["recency"] + normalized["frequency"] + normalized["monetary"]

    remaining = set(int(value) for value in centers.index)
    names: dict[int, str] = {}

    high = int(normalized["value"].idxmax())
    names[high] = "Khách hàng giá trị cao"
    remaining.discard(high)

    if remaining:
        at_risk = max(remaining, key=lambda cluster: float(centers.loc[cluster, "recency"]))
        if float(centers.loc[at_risk, "recency"]) >= max(60.0, float(centers["recency"].median())):
            names[at_risk] = "Có nguy cơ rời bỏ"
            remaining.discard(at_risk)

    if remaining:
        active = min(remaining, key=lambda cluster: float(centers.loc[cluster, "recency"]))
        names[active] = "Khách hàng đang hoạt động"
        remaining.discard(active)

    labels = ["Khách hàng tiềm năng", "Khách hàng cần nuôi dưỡng"]
    for index, cluster in enumerate(sorted(remaining)):
        names[cluster] = labels[min(index, len(labels) - 1)]
    return names


def build_customer_segments(df: pd.DataFrame) -> dict[str, Any]:
    if df.empty:
        return {
            "algorithm": "RFM",
            "method": "no-data",
            "segments": [],
            "message": "Chưa có khách hàng để phân nhóm.",
        }

    working = df.copy()
    for column in ("recency", "frequency", "monetary"):
        working[column] = pd.to_numeric(working[column], errors="coerce").fillna(0).astype(float)
    working["recency"] = working["recency"].clip(lower=0)
    working["frequency"] = working["frequency"].clip(lower=0)
    working["monetary"] = working["monetary"].clip(lower=0)

    non_buyers = working[working["frequency"] <= 0].copy()
    buyers = working[working["frequency"] > 0].copy()
    summaries: list[dict[str, Any]] = []
    method = "RFM rules"
    silhouette: float | None = None

    if not buyers.empty:
        unique_count = len(buyers[["recency", "frequency", "monetary"]].drop_duplicates())
        max_clusters = min(4, len(buyers) - 1, unique_count)
        best_labels: np.ndarray | None = None
        best_score = -1.0

        if len(buyers) >= 4 and max_clusters >= 2:
            transformed = np.log1p(buyers[["recency", "frequency", "monetary"]].to_numpy())
            scaled = StandardScaler().fit_transform(transformed)
            for cluster_count in range(2, max_clusters + 1):
                labels = KMeans(
                    n_clusters=cluster_count, random_state=42, n_init=20
                ).fit_predict(scaled)
                if len(set(labels)) < 2 or len(set(labels)) >= len(labels):
                    continue
                score = float(silhouette_score(scaled, labels))
                if score > best_score:
                    best_score = score
                    best_labels = labels

        if best_labels is not None:
            buyers["cluster"] = best_labels
            cluster_names = _unique_cluster_names(buyers)
            for cluster, group in buyers.groupby("cluster"):
                summaries.append(_segment_summary(group, int(cluster), cluster_names[int(cluster)]))
            method = "RFM + KMeans (k selected by silhouette)"
            silhouette = round(best_score, 4)
        else:
            buyers["segment_name"] = buyers.apply(
                lambda row: _rule_segment_name(row, buyers), axis=1
            )
            for cluster, (name, group) in enumerate(buyers.groupby("segment_name", sort=True)):
                summaries.append(_segment_summary(group, cluster, str(name)))

    if not non_buyers.empty:
        summaries.append(
            _segment_summary(non_buyers, -1, "Chưa phát sinh mua hàng")
        )

    summaries.sort(
        key=lambda item: (
            item["name"] == "Chưa phát sinh mua hàng",
            -item["avgMonetary"],
        )
    )
    response: dict[str, Any] = {
        "algorithm": "RFM customer segmentation",
        "method": method,
        "customersAnalyzed": int(len(working)),
        "buyersAnalyzed": int(len(buyers)),
        "segments": summaries,
    }
    if silhouette is not None:
        response["silhouetteScore"] = silhouette
    if buyers.empty:
        response["message"] = "Chưa có đơn hoàn thành; khách hàng được tách vào nhóm chưa mua."
    return response


@app.get("/ml/customer-segments", dependencies=[Depends(verify_internal_request)])
def customer_segments() -> dict[str, Any]:
    sql = """
        SELECT u.id AS user_id, u.full_name,
               COALESCE(EXTRACT(DAY FROM (CURRENT_TIMESTAMP - MAX(o.completed_at))), 0) AS recency,
               COUNT(DISTINCT o.id) AS frequency,
               COALESCE(SUM(o.total), 0) AS monetary
        FROM users u
        LEFT JOIN orders o ON o.user_id = u.id
            AND o.status = 'HOAN_THANH'
            AND o.completed_at IS NOT NULL
        WHERE u.role <> 'ROLE_ADMIN'
        GROUP BY u.id, u.full_name
        ORDER BY u.id
    """
    return build_customer_segments(query_df(sql))


# ---------------------------------------------------------------------------
# Demand forecasting with backtesting
# ---------------------------------------------------------------------------

def prepare_daily_history(
    sale_dates: pd.Series,
    quantities: pd.Series,
    today: pd.Timestamp | None = None,
    max_days: int = MAX_FORECAST_HISTORY_DAYS,
    available_from: Any | None = None,
) -> pd.Series:
    reference_day = (today or pd.Timestamp.now()).normalize()
    history_end = reference_day - pd.Timedelta(days=1)
    max_days = max(1, int(max_days))

    sales_frame = pd.DataFrame(
        {
            "sale_date": pd.to_datetime(pd.Series(sale_dates).reset_index(drop=True), errors="coerce"),
            "quantity": pd.to_numeric(pd.Series(quantities).reset_index(drop=True), errors="coerce").fillna(0),
        }
    ).dropna(subset=["sale_date"])
    sales_frame["sale_date"] = sales_frame["sale_date"].dt.normalize()

    start_candidates: list[pd.Timestamp] = []
    if not sales_frame.empty:
        start_candidates.append(sales_frame["sale_date"].min())

    if available_from is not None and not pd.isna(available_from):
        available_date = pd.to_datetime(available_from, errors="coerce")
        if not pd.isna(available_date):
            start_candidates.append(pd.Timestamp(available_date).normalize())

    if not start_candidates:
        return pd.Series(dtype=float)

    earliest_available = min(start_candidates)
    history_cutoff = history_end - pd.Timedelta(days=max_days - 1)
    start = max(earliest_available, history_cutoff)
    if start > history_end:
        return pd.Series(dtype=float)
    index = pd.date_range(start, history_end, freq="D")

    if sales_frame.empty:
        return pd.Series(0.0, index=index, dtype=float)

    grouped = pd.Series(
        sales_frame["quantity"].to_numpy(dtype=float),
        index=sales_frame["sale_date"],
        dtype=float,
    ).groupby(level=0).sum()
    return grouped.reindex(index, fill_value=0.0).clip(lower=0)


def forecast_recent_average(values: np.ndarray, horizon: int, window: int = 14) -> np.ndarray:
    if len(values) == 0:
        return np.zeros(horizon)
    recent = values[-min(window, len(values)):]
    return np.repeat(max(0.0, float(np.mean(recent))), horizon)


def forecast_weighted_average(values: np.ndarray, horizon: int) -> np.ndarray:
    if len(values) == 0:
        return np.zeros(horizon)
    windows = [(7, 0.55), (14, 0.30), (28, 0.15)]
    total_weight = 0.0
    estimate = 0.0
    for window, weight in windows:
        if len(values) >= min(window, 7):
            estimate += float(np.mean(values[-min(window, len(values)):])) * weight
            total_weight += weight
    estimate = estimate / total_weight if total_weight else float(np.mean(values))
    return np.repeat(max(0.0, estimate), horizon)


def forecast_weekday(history: pd.Series, horizon: int) -> np.ndarray:
    if history.empty:
        return np.zeros(horizon)
    recent_mean = float(history.tail(min(28, len(history))).mean())
    future_dates = pd.date_range(history.index[-1] + pd.Timedelta(days=1), periods=horizon, freq="D")
    predictions = []
    for date in future_dates:
        same_weekday = history[history.index.dayofweek == date.dayofweek].tail(8)
        weekday_mean = float(same_weekday.mean()) if not same_weekday.empty else recent_mean
        predictions.append(max(0.0, 0.75 * weekday_mean + 0.25 * recent_mean))
    return np.asarray(predictions, dtype=float)


def forecast_croston_sba(values: np.ndarray, horizon: int, alpha: float = 0.15) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    nonzero_positions = np.flatnonzero(values > 0)
    if len(nonzero_positions) == 0:
        return np.zeros(horizon)
    first = int(nonzero_positions[0])
    demand = float(values[first])
    interval = float(first + 1)
    previous = first
    for position in nonzero_positions[1:]:
        position = int(position)
        demand = demand + alpha * (float(values[position]) - demand)
        gap = float(position - previous)
        interval = interval + alpha * (gap - interval)
        previous = position
    rate = (1.0 - alpha / 2.0) * demand / max(interval, 1.0)
    return np.repeat(max(0.0, rate), horizon)


def forecast_damped_trend(values: np.ndarray, horizon: int, damping: float = 0.85) -> np.ndarray:
    values = np.asarray(values, dtype=float)
    if len(values) < 7:
        return forecast_recent_average(values, horizon, 7)
    smoothed = pd.Series(values).rolling(7, min_periods=1).mean().to_numpy()
    lookback = min(42, len(smoothed))
    y = smoothed[-lookback:]
    x = np.arange(lookback, dtype=float)
    slope, intercept = np.polyfit(x, y, 1)
    base = max(0.0, float(intercept + slope * (lookback - 1)))
    max_daily_change = max(0.25, max(base, float(np.mean(values[-7:]))) / 7.0)
    slope = float(np.clip(slope, -max_daily_change, max_daily_change))
    predictions = []
    accumulated = 0.0
    for step in range(1, horizon + 1):
        accumulated += damping ** step
        predictions.append(max(0.0, base + slope * accumulated))
    return np.asarray(predictions, dtype=float)


ForecastFunction = Callable[[pd.Series, int], np.ndarray]


def candidate_forecasts(history: pd.Series) -> dict[str, ForecastFunction]:
    candidates: dict[str, ForecastFunction] = {
        "trung-binh-gan": lambda series, horizon: forecast_weighted_average(series.to_numpy(), horizon),
        "trung-binh-14-ngay": lambda series, horizon: forecast_recent_average(series.to_numpy(), horizon, 14),
    }
    nonzero_ratio = float((history > 0).mean()) if not history.empty else 0.0
    sales_days = int((history > 0).sum())
    if len(history) >= 28:
        candidates["mua-vu-theo-thu"] = forecast_weekday
    if sales_days >= 2 and nonzero_ratio <= 0.40:
        candidates["croston-sba"] = lambda series, horizon: forecast_croston_sba(series.to_numpy(), horizon)
    if len(history) >= 21 and nonzero_ratio >= 0.15:
        candidates["xu-huong-giam-chan"] = lambda series, horizon: forecast_damped_trend(series.to_numpy(), horizon)
    return candidates


def choose_forecast_model(
    history: pd.Series,
) -> tuple[str, ForecastFunction, float | None, float | None]:
    candidates = candidate_forecasts(history)
    if len(history) < 14:
        preferred = "croston-sba" if "croston-sba" in candidates else "trung-binh-gan"
        return preferred, candidates[preferred], None, None

    validation_days = min(14, max(3, len(history) // 5))
    train = history.iloc[:-validation_days]
    actual = history.iloc[-validation_days:].to_numpy(dtype=float)
    best_name = "trung-binh-gan"
    best_function = candidates[best_name]
    best_mae = float("inf")
    best_score = float("inf")

    for name, function in candidates.items():
        train_candidates = candidate_forecasts(train)
        train_function = train_candidates.get(name)
        if train_function is None:
            continue
        predicted = train_function(train, validation_days)
        absolute_error = np.abs(actual - predicted)
        underforecast_penalty = np.maximum(actual - predicted, 0.0)
        mae = float(np.mean(absolute_error))
        score = float(mae + 0.15 * np.mean(underforecast_penalty))
        if score < best_score:
            best_name = name
            best_function = function
            best_mae = mae
            best_score = score
    return best_name, best_function, best_mae, best_score


def forecast_product(
    product_id: int,
    product_name: str,
    current_stock: int,
    history: pd.Series,
    days: int,
) -> dict[str, Any]:
    if history.empty or int((history > 0).sum()) == 0:
        return {
            "productId": int(product_id),
            "productName": str(product_name),
            "predictedUnits": 0.0,
            "recommendedRestock": 0,
            "method": "chưa có lịch sử bán",
            "confidence": "không đủ dữ liệu",
            "historyDays": int(len(history)),
            "salesDays": 0,
        }

    method, function, validation_mae, validation_score = choose_forecast_model(history)
    daily_prediction = np.clip(function(history, days), 0.0, None)
    predicted_units = float(np.sum(daily_prediction))
    recent = history.tail(min(28, len(history))).to_numpy(dtype=float)
    variability = float(np.std(recent, ddof=0)) if len(recent) > 1 else 0.0
    safety_stock = FORECAST_SERVICE_Z * variability * math.sqrt(min(days, 7))
    target_stock = int(math.ceil(predicted_units + safety_stock))
    restock = max(0, target_stock - max(0, int(current_stock)))
    sales_days = int((history > 0).sum())

    if len(history) >= 90 and sales_days >= 12 and validation_mae is not None:
        scale = max(1.0, float(history.mean()))
        confidence = "cao" if validation_mae / scale <= 0.75 else "trung bình"
    elif len(history) >= 30 and sales_days >= 5:
        confidence = "trung bình"
    else:
        confidence = "thấp"

    result: dict[str, Any] = {
        "productId": int(product_id),
        "productName": str(product_name),
        "predictedUnits": round(predicted_units, 1),
        "recommendedRestock": restock,
        "method": method,
        "confidence": confidence,
        "historyDays": int(len(history)),
        "salesDays": sales_days,
        "safetyStock": round(safety_stock, 1),
    }
    if validation_mae is not None:
        result["validationMae"] = round(float(validation_mae), 3)
    if validation_score is not None:
        result["validationScore"] = round(float(validation_score), 3)
    return result


@app.get("/ml/sales-forecast", dependencies=[Depends(verify_internal_request)])
def sales_forecast(days: int = Query(default=7, ge=1, le=30)) -> dict[str, Any]:
    products = query_df(
        "SELECT id AS product_id, name, stock, created_at "
        "FROM product WHERE active = true ORDER BY id"
    )
    if products.empty:
        return {
            "algorithm": "Backtested demand forecasting",
            "forecastDays": days,
            "predictions": [],
        }

    sales = query_df(
        """
        SELECT p.id AS product_id, DATE(o.completed_at) AS sale_date,
               SUM(oi.quantity) AS quantity
        FROM order_item oi
        JOIN orders o ON o.id = oi.order_id
        JOIN product p ON p.id = oi.product_id
        WHERE o.status = 'HOAN_THANH'
          AND o.completed_at IS NOT NULL
          AND p.active = true
        GROUP BY p.id, DATE(o.completed_at)
        ORDER BY p.id, sale_date
        """
    )
    if not sales.empty:
        sales["sale_date"] = pd.to_datetime(sales["sale_date"])

    today = pd.Timestamp.now().normalize()
    results = []
    for product in products.itertuples():
        group = (
            sales[sales["product_id"] == product.product_id]
            if not sales.empty
            else pd.DataFrame()
        )
        sale_dates = group["sale_date"] if not group.empty else pd.Series(dtype="datetime64[ns]")
        quantities = group["quantity"] if not group.empty else pd.Series(dtype=float)
        history = prepare_daily_history(
            sale_dates,
            quantities,
            today=today,
            available_from=product.created_at,
        )
        results.append(
            forecast_product(
                int(product.product_id),
                str(product.name),
                int(product.stock),
                history,
                days,
            )
        )

    results.sort(
        key=lambda item: (item["recommendedRestock"], item["predictedUnits"]),
        reverse=True,
    )
    return {
        "algorithm": "Backtested demand forecasting",
        "forecastDays": days,
        "generatedAt": pd.Timestamp.now().isoformat(),
        "predictions": results[:10],
        "note": "Khuyến nghị nhập = nhu cầu dự báo + tồn kho an toàn - tồn hiện tại.",
    }


# ---------------------------------------------------------------------------
# ResNet18 image retrieval with input and catalog quality checks
# ---------------------------------------------------------------------------

SHOE_KEYWORDS = (
    "shoe", "sneaker", "sandal", "slipper", "clog", "boot", "loafer",
    "running", "trainer", "footwear",
)


@lru_cache(maxsize=1)
def vision_bundle():
    weights = ResNet18_Weights.DEFAULT
    classifier = resnet18(weights=weights)
    classifier.eval()
    feature_extractor = torch.nn.Sequential(*list(classifier.children())[:-1])
    feature_extractor.eval()
    categories = list(weights.meta.get("categories", []))
    shoe_indices = [
        index
        for index, label in enumerate(categories)
        if any(keyword in label.lower() for keyword in SHOE_KEYWORDS)
    ]
    return classifier, feature_extractor, weights.transforms(), categories, shoe_indices


def analyze_image(image: Image.Image) -> tuple[np.ndarray, float, list[dict[str, Any]]]:
    classifier, feature_extractor, preprocess, categories, shoe_indices = vision_bundle()
    tensor = preprocess(image.convert("RGB")).unsqueeze(0)
    with torch.inference_mode():
        logits = classifier(tensor)
        features = feature_extractor(tensor).flatten(1).squeeze(0).cpu().numpy()
        probabilities = torch.softmax(logits, dim=1).squeeze(0).cpu().numpy()
    normalized = features / (np.linalg.norm(features) + 1e-12)
    shoe_confidence = float(probabilities[shoe_indices].sum()) if shoe_indices else 0.0
    top_indices = np.argsort(probabilities)[-5:][::-1]
    top_labels = [
        {
            "label": categories[int(index)] if int(index) < len(categories) else str(index),
            "probability": round(float(probabilities[int(index)]), 4),
        }
        for index in top_indices
    ]
    return normalized, shoe_confidence, top_labels


def embedding(image: Image.Image) -> np.ndarray:
    vector, _, _ = analyze_image(image)
    return vector


def open_image(payload: bytes) -> Image.Image:
    try:
        image = Image.open(io.BytesIO(payload))
        image.load()
        if image.width < 64 or image.height < 64:
            raise ValueError("Ảnh quá nhỏ; mỗi chiều phải từ 64 px.")
        return image.convert("RGB")
    except ValueError:
        raise
    except Exception as exc:
        raise ValueError("Dữ liệu không phải ảnh hợp lệ.") from exc


def _origin(url: str) -> str:
    parsed = urlparse(url)
    if not parsed.scheme or not parsed.hostname:
        return ""
    port = f":{parsed.port}" if parsed.port else ""
    return f"{parsed.scheme}://{parsed.hostname}{port}".rstrip("/")


def normalize_image_url(url: str) -> str:
    value = (url or "").strip()
    if value.startswith("/"):
        if not STORE_BASE_URL:
            raise ValueError("Ảnh nội bộ cần cấu hình AI_STORE_BASE_URL.")
        return urljoin(STORE_BASE_URL + "/", value.lstrip("/"))
    return value


def is_public_http_url(url: str) -> bool:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return False
    if _origin(url) in TRUSTED_IMAGE_ORIGINS:
        return True
    try:
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        addresses = {item[4][0] for item in socket.getaddrinfo(parsed.hostname, port)}
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
    current_url = normalize_image_url(url)
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
def product_embedding(image_url: str, image_version: str) -> np.ndarray:
    """Cache a vector by URL and the latest product update timestamp.

    ``image_version`` is intentionally part of the cache key. When an
    administrator replaces an image while keeping the same URL, JPA updates the
    product's ``updated_at`` value and the next search computes a fresh vector.
    """
    return embedding(download_image(image_url))


def filter_similarity_matches(
    matches: list[dict[str, Any]],
    limit: int,
    minimum: float = MIN_IMAGE_SIMILARITY,
) -> list[dict[str, Any]]:
    if not matches:
        return []
    ordered = sorted(matches, key=lambda item: item["similarity"], reverse=True)
    best = float(ordered[0]["similarity"])
    adaptive_minimum = max(minimum, best - 0.14)
    return [
        item for item in ordered if float(item["similarity"]) >= adaptive_minimum
    ][:limit]


def perform_image_search(payload: bytes, limit: int) -> dict[str, Any]:
    """Run CPU-, database- and network-heavy image search off the event loop."""
    try:
        query_vector, shoe_confidence, top_labels = analyze_image(open_image(payload))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail="Không tải được mô hình ResNet18. Hãy kiểm tra kết nối mạng.",
        ) from exc

    if shoe_confidence < MIN_SHOE_CONFIDENCE:
        raise HTTPException(
            status_code=422,
            detail="Ảnh tải lên không được nhận diện đủ giống giày. Hãy dùng ảnh rõ toàn bộ đôi giày.",
        )

    products = query_df(
        "SELECT id, name, brand, image_url, updated_at "
        "FROM product WHERE active = true AND image_url IS NOT NULL "
        "AND BTRIM(image_url) <> '' ORDER BY id"
    )
    raw_product_count = int(len(products))
    products_by_image: dict[str, list[Any]] = {}
    invalid_image_urls = 0
    for row in products.itertuples():
        try:
            normalized_url = normalize_image_url(str(row.image_url))
        except ValueError:
            invalid_image_urls += 1
            continue
        products_by_image.setdefault(normalized_url, []).append(row)

    warnings: list[str] = []
    duplicate_product_count = sum(
        max(0, len(rows) - 1) for rows in products_by_image.values()
    )
    if duplicate_product_count:
        warnings.append(
            f"Danh mục có {duplicate_product_count} sản phẩm dùng chung ảnh; "
            "hệ thống tái sử dụng vector ảnh nhưng vẫn xếp hạng từng sản phẩm."
        )
    if invalid_image_urls:
        warnings.append(
            f"Có {invalid_image_urls} sản phẩm có URL ảnh không hợp lệ và không thể so sánh."
        )
    if raw_product_count > 1 and len(products_by_image) < 2:
        warnings.append(
            "Danh mục chưa có đủ ảnh sản phẩm khác nhau để xếp hạng đáng tin cậy."
        )

    scored = []
    failed_images = 0
    failed_products = 0
    for normalized_url, rows in products_by_image.items():
        image_version = max(
            (
                pd.Timestamp(row.updated_at).isoformat()
                for row in rows
                if row.updated_at is not None and not pd.isna(row.updated_at)
            ),
            default="",
        )
        try:
            product_vector = product_embedding(normalized_url, image_version)
        except Exception:
            failed_images += 1
            failed_products += len(rows)
            continue

        for row in rows:
            score = float(np.dot(query_vector, product_vector))
            scored.append(
                {
                    "productId": int(row.id),
                    "name": row.name,
                    "brand": row.brand,
                    "imageUrl": row.image_url,
                    "similarity": round(score, 4),
                }
            )
    if failed_images:
        warnings.append(
            f"Không tải hoặc phân tích được {failed_images} ảnh đại diện, "
            f"ảnh hưởng đến {failed_products} sản phẩm."
        )

    matches = filter_similarity_matches(scored, limit)
    message = None
    if not matches:
        message = "Không có sản phẩm nào vượt ngưỡng tương đồng. Hãy thử ảnh giày rõ hơn."
    return {
        "model": "ResNet18 pretrained feature retrieval",
        "queryShoeConfidence": round(shoe_confidence, 4),
        "topImageLabels": top_labels,
        "minimumSimilarity": MIN_IMAGE_SIMILARITY,
        "catalogProducts": raw_product_count,
        "catalogUniqueImages": len(products_by_image),
        "matches": matches,
        "warnings": warnings,
        "message": message,
    }


@app.post("/dl/image-search", dependencies=[Depends(verify_internal_request)])
async def image_search(
    file: UploadFile = File(...), limit: int = Query(default=6, ge=1, le=12)
) -> dict[str, Any]:
    if file.content_type not in ALLOWED_IMAGE_TYPES:
        raise HTTPException(
            status_code=415,
            detail="Chỉ hỗ trợ ảnh JPEG, PNG hoặc WebP.",
        )

    try:
        payload = await file.read(MAX_UPLOAD_BYTES + 1)
    finally:
        await file.close()

    if len(payload) > MAX_UPLOAD_BYTES:
        raise HTTPException(status_code=413, detail="Ảnh tải lên vượt quá 10 MB.")

    return await run_in_threadpool(perform_image_search, payload, limit)
