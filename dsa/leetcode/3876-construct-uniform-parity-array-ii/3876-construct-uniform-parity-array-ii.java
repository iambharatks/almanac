class Solution {
    public boolean uniformArray(int[] nums) {
        int even = nums.length;
        int odd =0;
        int minE = Integer.MAX_VALUE;
        int minO = Integer.MAX_VALUE;
        for(int i : nums){
            if(i%2==1) {
                even--;odd++;
                minO = Math.min(minO,i);
            }else
                minE = Math.min(minE,i);
        }
        if(even == 0 || odd == 0) return true;
        int min = Math.min(minO,minE);
        for(int i : nums){
            if(i > min){
                if(i%2 == min%2) continue;
                if(i%2 == 0) continue;
                if(i > minO) continue;
                return false;
            }
        }
        return true;
    }
}