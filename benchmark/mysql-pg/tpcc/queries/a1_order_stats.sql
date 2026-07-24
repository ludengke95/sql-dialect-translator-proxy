-- TPC-C A1: 各区域订单量统计
SELECT d_id, COUNT(*) AS order_count, COUNT(DISTINCT o_c_id) AS unique_customers
FROM orders, district
WHERE o_w_id = 1 AND d_w_id = 1 AND d_id = o_d_id
GROUP BY d_id
ORDER BY d_id;