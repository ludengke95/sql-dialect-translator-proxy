-- TPC-C T4: Delivery 事务
UPDATE order_line
SET ol_delivery_d = NOW()
WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = (
    SELECT no_o_id FROM new_orders WHERE no_w_id = 1 AND no_d_id = 1 ORDER BY no_o_id ASC LIMIT 1
);