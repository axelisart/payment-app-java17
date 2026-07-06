package com.demo.payment.repository;

// No changes from the Java 11 original.
import com.demo.payment.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findTop20ByOrderByCreatedAtDesc();
}
