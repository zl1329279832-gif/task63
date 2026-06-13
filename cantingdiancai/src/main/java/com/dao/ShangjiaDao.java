package com.dao;

import com.entity.ShangjiaEntity;
import com.baomidou.mybatisplus.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.plugins.pagination.Pagination;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.entity.view.ShangjiaView;

/**
 * 店家 Dao 接口
 *
 * @author
 */
public interface ShangjiaDao extends BaseMapper<ShangjiaEntity> {

   List<ShangjiaView> selectListView(Pagination page,@Param("params")Map<String,Object> params);

   @Update("UPDATE shangjia SET new_money = new_money + #{amount} WHERE id = #{id}")
   int addMoney(@Param("id") Integer id, @Param("amount") Double amount);

   @Update("UPDATE shangjia SET new_money = new_money - #{amount} WHERE id = #{id} AND new_money >= #{amount}")
   int deductMoney(@Param("id") Integer id, @Param("amount") Double amount);

}
