-- Source: live AdMob Ads Activity report, read 2026-08-31 in this audit.
-- App: NULL PLAYING / com.nullplaying. Source: AdMob Network.
-- Console range: 2026-08-01 through 2026-08-31 (month to date).
-- These are manually transcribed aggregate console rows, NOT a query against
-- Google's internal database. USD amounts are the rounded displayed values.
-- Purpose: reproduce the chart rows and show-rate calculations transparently.
WITH console_rows(date, iso_date, requests, matched, impressions,
                  earnings_usd_display, ecpm_usd) AS (
  VALUES
    ('8월 28일', '2026-08-28', 3, 3, 0, 0.00, NULL),
    ('8월 29일', '2026-08-29', 36, 36, 1, 0.01, 5.63),
    ('8월 30일', '2026-08-30', 42, 42, 4, 0.02, 5.13),
    ('8월 31일', '2026-08-31', 10, 10, 0, 0.00, NULL)
), plotted AS (
  SELECT *, 1 AS metric_order, '요청' AS metric, requests AS count
    FROM console_rows
  UNION ALL
  SELECT *, 2 AS metric_order, '노출' AS metric, impressions AS count
    FROM console_rows
)
SELECT date, iso_date, requests, matched, impressions, earnings_usd_display,
       ecpm_usd, 1.0 * impressions / NULLIF(matched, 0) AS show_rate,
       metric, count, '널플레잉-보상광고' AS ad_unit
  FROM plotted
 ORDER BY iso_date, metric_order;
