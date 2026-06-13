package com.dao;

import com.entity.ShangpinEntity;
import com.baomidou.mybatisplus.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.plugins.pagination.Pagination;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import com.entity.view.ShangpinView;

/**
 * 商品 Dao 接口
 *
 * @author
 */
public interface ShangpinDao extends BaseMapper<ShangpinEntity> {

   List<ShangpinView> selectListView(Pagination page,@Param("params")Map<String,Object> params);

   @Update("UPDATE shangpin SET shangpin_kucun_number = shangpin_kucun_number - #{buyNumber} WHERE id = #{id} AND shangpin_kucun_number >= #{buyNumber}")
   int deductKucun(@Param("id") Integer id, @Param("buyNumber") Integer buyNumber);

   @Update("UPDATE shangpin SET shangpin_kucun_number = shangpin_kucun_number + #{buyNumber} WHERE id = #{id}")
   int restoreKucun(@Param("id") Integer id, @Param("buyNumber") Integer buyNumber);

}
