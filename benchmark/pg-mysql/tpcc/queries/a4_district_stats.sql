-- TPC-C A4: District 统计
SELECT d_id, d_name, d_ytd, d_next_o_id
FROM district
WHERE d_w_id = 1
ORDER BY d_id;