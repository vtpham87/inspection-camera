import os
import json
from unittest.mock import patch, MagicMock
import pytest
from config import PhotoConfig, load_config, save_config
import firebase_sync

def test_sync_config_from_firebase_resolutions(tmp_path):
    config_file = str(tmp_path / "photo_config.json")
    cfg = PhotoConfig(photo_resolution="original")
    save_config(cfg, config_file)

    test_resolutions = [
        ("high", "high"),
        ("4k", "high"),
        ("medium", "medium"),
        ("fhd", "medium"),
        ("1080p", "medium"),
        ("low", "low"),
        ("hd", "low"),
        ("720p", "low"),
        ("original", "original"),
    ]

    with patch.object(firebase_sync, "CONFIG_PATH", config_file):
        for input_res, expected_res in test_resolutions:
            mock_data = {"photo_resolution": input_res}
            with patch.object(firebase_sync, "rtdb_request") as mock_rtdb, \
                 patch.object(firebase_sync, "sync_config_to_firebase") as mock_sync_to:
                # Return mock_data for the first node GET config_update, None for DELETE
                mock_rtdb.side_effect = lambda url, path, method="GET", data=None: mock_data if path == "config_update" and method == "GET" else None

                result = firebase_sync.sync_config_from_firebase()
                assert result is True
                saved = load_config(config_file)
                assert saved.photo_resolution == expected_res
