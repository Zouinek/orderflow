ALTER TABLE orders
    ALTER COLUMN customer_name SET NOT NULL,
    ALTER COLUMN item          SET NOT NULL,
    ALTER COLUMN price         SET NOT NULL,
    ALTER COLUMN created_at    SET NOT NULL,
    ALTER COLUMN created_at    SET DEFAULT now(),
    DROP CONSTRAINT IF EXISTS orders_price_check,
    ADD  CONSTRAINT orders_price_check    CHECK (price >= 0),
    DROP CONSTRAINT IF EXISTS orders_quantity_check,
    ADD  CONSTRAINT orders_quantity_check CHECK (quantity > 0);
