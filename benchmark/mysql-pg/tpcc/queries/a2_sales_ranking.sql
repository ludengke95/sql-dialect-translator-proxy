-- TPC-C A2: 商品销售排行
SELECT i_id, i_name, SUM(ol_quantity) AS total_qty, AVG(ol_amount) AS avg_amount
FROM item, order_line
WHERE i_id = ol_i_id AND ol_w_id = 1
GROUP BY i_id, i_name
ORDER BY total_qty DESC
LIMIT 10;