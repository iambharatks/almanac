class Solution {
    public int firstStableIndex(int[] nums, int k) {
        int n = nums.length;
        int[] minV = nums.clone();
        for(int i = n-2; i >= 0 ; i--){
            minV[i] = Math.min(minV[i+1],minV[i]);
        }
        int maxV = 0;
        int res = -1;
        for(int i = 0 ; i < n ; i++){
            maxV = Math.max(maxV,nums[i]);
            res = (maxV-k <= minV[i])?i:res;
            if(res != -1) return res;
        }
        return res;
    }
}