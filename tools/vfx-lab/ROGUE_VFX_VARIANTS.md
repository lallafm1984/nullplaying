# 도적 웹 VFX 최종 확정 상태

도적 20개 스킬은 2026-08-25 웹 선택 상태를 기준으로 모두 확정했다. Lv.1 `rogue_t01_c01` 빠른 찌르기는 사용자 지정으로 **1안**을 확정했고, Lv.5~70은 화면에 표시된 기존 확정안, Lv.75~95는 당시 선택된 **원본**을 확정했다.

Lv.95 `rogue_t20_c05` 죽음의 한 점의 원본은 앞서 이동한 빠른 찌르기 원본 이미지다. 이제 별도 오버라이드가 아니라 `custom-assets/rogue-finalized-vfx/rogue_t20_c05.png`에 최종 확정본으로 통합했다.

Lv.55 `rogue_t12_c05` 침묵의 처형은 **4안 · 월영 역날 처형**으로 확정했다. 확정본은 4×4, 16프레임, 1444×640 RGBA이며 F01~F14의 연속 순서를 유지한다. F15는 F14를 반복하고 F16은 투명하다. 소재 자체 알파에 추가 배율을 적용하지 않으며 남은 소멸은 웹 런타임 페이드가 처리한다.

선택되지 않은 시안, 생성 작업·프롬프트 매니페스트, 검수 이미지, 교체 전 확정본은 영구 삭제하지 않고 macOS 휴지통의 전용 복구 폴더로 옮겼다. 활성 도적 PNG는 `custom-assets/rogue-finalized-vfx`의 20개만 남는다. 원래 분류와 복구 위치는 `data/rogue-vfx-recovery-archive.json`에 기록한다.

활성 선택, 원본 및 확정 시안의 파일 해시는 `data/rogue-vfx-variants.json`이 관리한다.

```bash
python3 tools/vfx-lab/verify_rogue_vfx_variants.py
```

이 작업은 웹 테스터만 변경하며 Android 리소스에는 동기화하지 않는다.
