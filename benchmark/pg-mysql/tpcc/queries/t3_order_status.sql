-- TPC-C T3: Order-Status 事务

-- T3-a
SELECT c_id, c_first, c_middle, c_last, c_balance
FROM customer
WHERE c_w_id = 1 AND c_d_id = 1 AND c_last = 'BAR'
ORDER BY c_first;

-- T3-b
SELECT o_id, o_entry_d, o_carrier_id
FROM orders
WHERE o_w_id = 1 AND o_d_id = 1 AND o_c_id = 1
ORDER BY o_id DESC
LIMIT 1;

-- T3-c
SELECT ol_i_id, ol_supply_w_id, ol_quantity, ol_amount, ol_delivery_d
FROM order_line
WHERE ol_w_id = 1 AND ol_d_id = 1 AND ol_o_id = (
    SELECT MAX(o_id) FROM orders WHERE o_w_id = 1 AND o_d_id = 1 AND o_c_id = 1
)
ORDER BY ol_number;