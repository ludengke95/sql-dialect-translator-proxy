-- TPC-C T5: Stock-Level 事务

-- T5-a
SELECT d_next_o_id FROM district WHERE d_w_id = 1 AND d_id = 1;

-- T5-b
SELECT COUNT(DISTINCT ol_i_id) AS low_stock_count
FROM order_line, stock
WHERE ol_w_id = 1
    AND ol_d_id = 1
    AND ol_o_id BETWEEN (
        SELECT d_next_o_id - 20 FROM district WHERE d_w_id = 1 AND d_id = 1
    ) AND (
        SELECT d_next_o_id - 1 FROM district WHERE d_w_id = 1 AND d_id = 1
    )
    AND s_i_id = ol_i_id
    AND s_w_id = 1
    AND s_quantity < 10;